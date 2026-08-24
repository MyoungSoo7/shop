import React, { useCallback, useEffect, useState } from 'react';
import { adminApi } from '@/api/admin';
import {
  orderWorkflowApi,
  isAwaitingApproval,
  ORDER_STATUS_LABEL,
  OrderStatusValue,
} from '@/api/orderWorkflow';
import { OrderResponse } from '@/types';
import Spinner from '@/components/Spinner';
import { errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

const fmtAmount = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const fmtDate = (s: string) =>
  new Date(s).toLocaleString('ko-KR', {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });

const label = (status: string) =>
  ORDER_STATUS_LABEL[status as OrderStatusValue] ?? status;

/* ─────────────────────────────────────────
   승인 대기 1건
───────────────────────────────────────── */
const ApprovalRow: React.FC<{
  order: OrderResponse;
  onApproved: (order: OrderResponse) => void;
}> = ({ order, onApproved }) => {
  const { showToast } = useToast();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const isCancellation = order.status === 'CANCELLATION_REQUESTED';

  const approve = async () => {
    setBusy(true);
    try {
      // 사유는 운영자 기록용이라 비어 있어도 서버가 받는다. 빈 값을 굳이 막지 않는 대신
      // 무엇을 승인했는지는 남도록 기본 문구를 채워 보낸다.
      const note = reason.trim() === '' ? '운영자 승인' : reason.trim();
      const updated = isCancellation
        ? await orderWorkflowApi.approveCancellation(order.id, note)
        : await orderWorkflowApi.approveRefund(order.id, note);
      onApproved(updated);
      showToast(isCancellation ? '취소를 승인했습니다.' : '환불을 승인했습니다.', 'success');
    } catch (err) {
      showToast(errorDetail(err, '승인에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-bold text-gray-900">주문 #{order.id}</p>
          <p className="text-xs text-gray-400 mt-0.5">
            사용자 #{order.userId} · {fmtDate(order.createdAt)}
          </p>
          <p className="text-sm font-semibold text-blue-700 mt-1">{fmtAmount(order.amount)}</p>
        </div>
        <span
          className={`px-2 py-0.5 rounded-full text-xs font-semibold ${
            isCancellation ? 'bg-orange-100 text-orange-800' : 'bg-purple-100 text-purple-800'
          }`}
        >
          {label(order.status)}
        </span>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <input
          aria-label="승인 메모"
          placeholder="승인 메모 (선택)"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          className="flex-1 min-w-[12rem] border border-gray-300 rounded px-2 py-1.5 text-sm"
        />
        <button
          type="button"
          disabled={busy}
          onClick={() => void approve()}
          className="px-3 py-1.5 text-sm font-semibold rounded bg-gray-900 text-white disabled:opacity-40"
        >
          {isCancellation ? '취소 승인' : '환불 승인'}
        </button>
      </div>
    </div>
  );
};

/* ─────────────────────────────────────────
   취소·환불 승인 큐
───────────────────────────────────────── */
const OrderApprovalPage: React.FC = () => {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const all = await adminApi.getAllOrders();
      setOrders([...all].sort((a, b) => b.id - a.id));
    } catch (err) {
      setError(errorDetail(err, '주문 목록을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const handleApproved = (updated: OrderResponse) => {
    setOrders((prev) => prev.map((o) => (o.id === updated.id ? updated : o)));
  };

  // 승인 대기 = 신청 상태 그 자체. 별도 큐 테이블이 없으므로 상태로 골라낸다.
  const pending = orders.filter((o) => isAwaitingApproval(o.status));

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto">
        <div className="flex items-center justify-between mb-5">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">취소·환불 승인</h1>
            <p className="text-sm text-gray-500 mt-1">
              사용자가 신청한 건을 운영자가 승인합니다. 종단 상태는 승인으로만 만들어집니다.
            </p>
          </div>
          <button
            onClick={() => void load()}
            className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 text-gray-700 bg-white"
          >
            새로고침
          </button>
        </div>

        {loading ? (
          <Spinner size="md" message="승인 대기 건 불러오는 중..." />
        ) : error ? (
          <p className="text-center text-red-600 py-8">{error}</p>
        ) : pending.length === 0 ? (
          <div className="text-center py-16 text-gray-400 bg-white rounded-xl border border-gray-200">
            <p className="text-sm">승인 대기 중인 취소·환불 신청이 없습니다.</p>
          </div>
        ) : (
          <div className="space-y-3">
            <p className="text-sm text-gray-500">{pending.length}건 대기 중</p>
            {pending.map((order) => (
              <ApprovalRow key={order.id} order={order} onApproved={handleApproved} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default OrderApprovalPage;
