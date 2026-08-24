import React, { useState } from 'react';
import { OrderResponse } from '@/types';
import {
  orderWorkflowApi,
  canRequestCancellation,
  canRequestRefund,
} from '@/api/orderWorkflow';
import { errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

interface OrderRequestActionsProps {
  order: OrderResponse;
  onUpdated: (order: OrderResponse) => void;
}

/**
 * 사용자의 취소·환불 <b>신청</b> 버튼 — 즉시 상태를 종단으로 보내지 않는다.
 *
 * <p>신청은 CANCELLATION_REQUESTED·REFUND_REQUESTED 까지만 밀고, 종단(CANCELED·REFUNDED)은
 * 운영자 승인이 만든다. 사용자가 스스로 환불을 완료시킬 수 있으면 그건 워크플로가 아니라 구멍이다.
 *
 * <p>사유는 필수로 받는다 — 승인 화면에서 운영자가 판단할 근거가 그것뿐이다.
 */
const OrderRequestActions: React.FC<OrderRequestActionsProps> = ({ order, onUpdated }) => {
  const { showToast } = useToast();
  const [mode, setMode] = useState<'idle' | 'cancel' | 'refund'>('idle');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const cancellable = canRequestCancellation(order.status);
  const refundable = canRequestRefund(order.status);

  if (!cancellable && !refundable) return null;

  const submit = async () => {
    const trimmed = reason.trim();
    if (trimmed === '') return;
    setBusy(true);
    try {
      const updated =
        mode === 'cancel'
          ? await orderWorkflowApi.requestCancellation(order.id, trimmed)
          : await orderWorkflowApi.requestRefund(order.id, trimmed);
      onUpdated(updated);
      setMode('idle');
      setReason('');
      showToast(mode === 'cancel' ? '취소를 신청했습니다.' : '환불을 신청했습니다.', 'success');
    } catch (err) {
      showToast(errorDetail(err, '신청에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  if (mode !== 'idle') {
    return (
      <div className="mt-3 pt-3 border-t border-gray-100">
        <label className="block text-xs font-medium text-gray-700 mb-1">
          {mode === 'cancel' ? '취소 사유' : '환불 사유'}
        </label>
        <textarea
          aria-label={mode === 'cancel' ? '취소 사유' : '환불 사유'}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={2}
          placeholder="운영자가 판단할 수 있도록 사유를 적어주세요."
          className="w-full border border-gray-300 rounded px-2 py-1.5 text-sm"
        />
        <div className="flex gap-2 mt-2">
          <button
            type="button"
            disabled={busy || reason.trim() === ''}
            onClick={() => void submit()}
            className="px-3 py-1.5 text-xs font-semibold rounded bg-blue-600 text-white disabled:opacity-40"
          >
            {mode === 'cancel' ? '취소 신청' : '환불 신청'}
          </button>
          <button
            type="button"
            onClick={() => { setMode('idle'); setReason(''); }}
            className="px-3 py-1.5 text-xs rounded border border-gray-300 text-gray-600"
          >
            닫기
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="mt-3 pt-3 border-t border-gray-100 flex gap-2">
      {cancellable && (
        <button
          type="button"
          onClick={() => setMode('cancel')}
          className="px-3 py-1.5 text-xs font-medium rounded border border-gray-300 text-gray-700"
        >
          취소 신청
        </button>
      )}
      {refundable && (
        <button
          type="button"
          onClick={() => setMode('refund')}
          className="px-3 py-1.5 text-xs font-medium rounded border border-gray-300 text-gray-700"
        >
          환불 신청
        </button>
      )}
    </div>
  );
};

export default OrderRequestActions;
