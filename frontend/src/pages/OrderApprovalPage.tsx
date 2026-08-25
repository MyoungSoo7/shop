import React, { useCallback, useEffect, useState } from 'react';
import { adminApi } from '@/api/admin';
import {
  orderWorkflowApi,
  AWAITING_APPROVAL_STATUSES,
  isAwaitingApproval,
  ORDER_STATUS_LABEL,
  OrderStatusValue,
} from '@/api/orderWorkflow';
import { OrderResponse } from '@/types';
import Spinner from '@/components/Spinner';
import { errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

/**
 * 승인 큐 한 번에 받는 건수. 서버 상한(200)과 같게 잡는다 — 큐는 밀리면 안 되는 목록이라
 * 최대한 많이 보여 주되, 그래도 넘치면 넘쳤다고 화면에 적는다.
 */
const QUEUE_PAGE_SIZE = 200;

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
  /** 대기 건 전체 규모. 화면에 실린 건수와 다를 수 있고, 다르면 그렇게 말한다. */
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /**
   * 승인 대기 건만 <b>서버에서</b> 걸러 받는다.
   *
   * <p>예전에는 전 주문을 받아 브라우저가 걸러냈다. 목록에 페이징이 붙은 지금 그 방식은
   * 첫 페이지 밖의 대기 건을 조용히 빠뜨린다 — 큐가 비어 보이는데 실제로는 밀려 있고,
   * 화면에는 아무 경고도 뜨지 않는다.
   *
   * <p>총 건수는 페이지가 아니라 서버 집계(totalElements)에서 읽는다.
   */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await adminApi.getOrders({
        status: [...AWAITING_APPROVAL_STATUSES],
        page: 0,
        size: QUEUE_PAGE_SIZE,
      });
      setOrders(page.content);
      setTotal(page.totalElements);
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

  /**
   * 서버가 이미 대기 상태만 보냈지만 한 번 더 거른다 — 승인 직후 그 자리에서 상태가 바뀐
   * 행을 큐에서 빼기 위해서다(재조회 없이). 서버 필터를 대신하는 것이 아니다.
   */
  const pending = orders.filter((o) => isAwaitingApproval(o.status));

  /** 화면에 실리지 못한 대기 건 수. 0 이 아니면 반드시 화면에 적는다. */
  const hidden = Math.max(total - orders.length, 0);

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
            <p className="text-sm text-gray-500">
              {total.toLocaleString()}건 대기 중
              {hidden > 0 && (
                // 큐가 한 화면을 넘겼다는 사실을 감추면, 운영자는 보이는 것이 전부라고 믿는다.
                <span className="text-amber-600"> · {hidden.toLocaleString()}건은 다음 새로고침에서</span>
              )}
            </p>
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
