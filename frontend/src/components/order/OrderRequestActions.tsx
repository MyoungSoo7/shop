import React, { useCallback, useEffect, useState } from 'react';
import { OrderResponse } from '@/types';
import { orderApi } from '@/api/order';
import {
  orderWorkflowApi,
  canRequestCancellation,
  canRequestRefund,
  canRequestExchange,
  hasOpenRequest,
} from '@/api/orderWorkflow';
import {
  returnRequestApi,
  isOpenReturnRequest,
  bankLabel,
  returnReasonLabel,
  BANK_OPTIONS,
  REASON_CODES_BY_TYPE,
  RETURN_REQUEST_STATUS_LABEL,
  RETURN_REQUEST_TYPE_LABEL,
  type ReturnRequestResponse,
  type ReturnRequestType,
} from '@/api/returnRequest';
import { errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

interface OrderRequestActionsProps {
  order: OrderResponse;
  onUpdated: (order: OrderResponse) => void;
}

/**
 * 사용자의 취소·반품·교환 <b>신청</b> 버튼 — 즉시 상태를 종단으로 보내지 않는다.
 *
 * <p>신청은 …_REQUESTED 까지만 밀고, 종단(CANCELED·REFUNDED)은 운영자 승인이 만든다.
 * 사용자가 스스로 환불을 완료시킬 수 있으면 그건 워크플로가 아니라 구멍이다.
 *
 * <p>세 유형이 모두 <b>신청 레코드</b>({@code /orders/{id}/return-requests})로 간다.
 * 예전 {@code cancellation-request}·{@code refund-request} 경로는 상태만 밀고 사유 문자열
 * 하나만 남겨서, 회수 송장과 환불 계좌를 적을 자리가 없었다 — 그 둘은 결국 전화와 메모로 흘렀다.
 * 서버 쪽 상태 전이는 두 경로가 <b>같은 유스케이스</b>를 부르므로 주문에 일어나는 일은 같고,
 * 달라지는 것은 남는 기록뿐이다.
 *
 * <p>철회는 두 시대를 모두 받는다. 레코드가 있으면 레코드를 닫으면서 주문을 되돌리고, 이 변경
 * 이전에 신청되어 <b>레코드가 없는 주문</b>은 옛 경로로 되돌린다. 새 경로만 남기면 이미 신청
 * 상태로 떠 있던 주문들이 철회 불가로 묶인다.
 */
const OrderRequestActions: React.FC<OrderRequestActionsProps> = ({ order, onUpdated }) => {
  const { showToast } = useToast();
  const [mode, setMode] = useState<'idle' | ReturnRequestType>('idle');
  const [reasonCode, setReasonCode] = useState('');
  const [reasonDetail, setReasonDetail] = useState('');
  const [account, setAccount] = useState({ bankCode: '', accountNumber: '', holderName: '' });
  const [waybill, setWaybill] = useState({ carrier: '', trackingNumber: '' });
  const [open, setOpen] = useState<ReturnRequestResponse | null>(null);
  const [busy, setBusy] = useState(false);

  const pending = hasOpenRequest(order.status);

  /** 열린 신청 조회 — 이게 있어야 계좌·송장을 어디에 붙일지 정해진다. */
  const loadOpen = useCallback(async () => {
    try {
      const history = await returnRequestApi.history(order.id);
      setOpen(history.find((r) => isOpenReturnRequest(r.status)) ?? null);
    } catch {
      // 조회 실패가 철회까지 막지는 않게 한다 — 레코드가 없는 옛 주문과 같은 취급.
      setOpen(null);
    }
  }, [order.id]);

  useEffect(() => {
    if (!pending) { setOpen(null); return; }
    void loadOpen();
  }, [pending, loadOpen]);

  const refreshOrder = async () => onUpdated(await orderApi.getOrder(order.id));

  const withdraw = async () => {
    setBusy(true);
    try {
      if (open) await returnRequestApi.withdraw(order.id, open.id);
      else await orderWorkflowApi.withdrawRequest(order.id);
      await refreshOrder();
      showToast('신청을 철회했습니다.', 'success');
    } catch (err) {
      showToast(errorDetail(err, '철회에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const saveAccount = async () => {
    if (!open) return;
    setBusy(true);
    try {
      setOpen(await returnRequestApi.changeRefundAccount(order.id, open.id, account));
      setAccount({ bankCode: '', accountNumber: '', holderName: '' });
      showToast('환불 계좌를 등록했습니다.', 'success');
    } catch (err) {
      showToast(errorDetail(err, '계좌 등록에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const saveWaybill = async () => {
    if (!open) return;
    setBusy(true);
    try {
      setOpen(await returnRequestApi.registerWaybill(order.id, open.id, waybill));
      setWaybill({ carrier: '', trackingNumber: '' });
      showToast('회수 송장을 등록했습니다.', 'success');
    } catch (err) {
      showToast(errorDetail(err, '송장 등록에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  if (pending) {
    // 계좌를 기다리는 동안은 환불이 실행되지 않는다 — 그 사실을 말해 주지 않으면
    // 고객은 승인을 기다리는 줄 알고, 운영자는 고객을 기다린다.
    const needsAccount = open?.awaitsRefundAccount === true;
    // 승인된 뒤에만 권한다. 서버는 승인 전 등록도 받아 주지만(이미 보낸 고객을 막을 이유가
    // 없다), 화면이 먼저 권하면 승인되지 않을 반품이 배송비를 쓰고 되돌아온다.
    const needsWaybill =
      open != null && open.type !== 'CANCEL' && open.status === 'APPROVED'
      && open.returnTrackingNumber == null;

    return (
      <div className="mt-3 pt-3 border-t border-gray-100 space-y-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs text-gray-500">
            {open
              ? `${RETURN_REQUEST_TYPE_LABEL[open.type]} 신청 · ${RETURN_REQUEST_STATUS_LABEL[open.status]}`
              : '운영자 승인을 기다리는 중입니다.'}
          </span>
          <button
            type="button"
            disabled={busy}
            onClick={() => void withdraw()}
            className="px-3 py-1.5 text-xs font-medium rounded border border-gray-300 text-gray-700 disabled:opacity-40"
          >
            신청 철회
          </button>
        </div>

        {open?.refundAccountNumberMasked && (
          <p className="text-xs text-gray-500">
            환불 계좌 {bankLabel(open.refundBankCode)} {open.refundAccountNumberMasked}{' '}
            {open.refundAccountHolder}
          </p>
        )}

        {needsAccount && (
          <div className="rounded border border-amber-200 bg-amber-50 p-2 space-y-1.5">
            <p className="text-xs text-amber-800">
              무통장·가상계좌 결제라 카드처럼 되돌릴 수 없습니다. 환불받을 계좌를 등록해 주세요.
            </p>
            <div className="flex flex-wrap gap-1.5">
              <select
                aria-label="환불 은행"
                value={account.bankCode}
                onChange={(e) => setAccount({ ...account, bankCode: e.target.value })}
                className="border border-gray-300 rounded px-2 py-1 text-xs"
              >
                <option value="">은행 선택</option>
                {BANK_OPTIONS.map((b) => (
                  <option key={b.code} value={b.code}>{b.name}</option>
                ))}
              </select>
              <input
                aria-label="환불 계좌번호"
                value={account.accountNumber}
                onChange={(e) => setAccount({ ...account, accountNumber: e.target.value })}
                placeholder="계좌번호"
                className="border border-gray-300 rounded px-2 py-1 text-xs flex-1 min-w-[8rem]"
              />
              <input
                aria-label="예금주"
                value={account.holderName}
                onChange={(e) => setAccount({ ...account, holderName: e.target.value })}
                placeholder="예금주"
                className="border border-gray-300 rounded px-2 py-1 text-xs w-24"
              />
              <button
                type="button"
                disabled={
                  busy ||
                  account.bankCode === '' ||
                  account.accountNumber.trim() === '' ||
                  account.holderName.trim() === ''
                }
                onClick={() => void saveAccount()}
                className="px-3 py-1 text-xs font-semibold rounded bg-amber-600 text-white disabled:opacity-40"
              >
                계좌 등록
              </button>
            </div>
          </div>
        )}

        {needsWaybill && (
          <div className="rounded border border-gray-200 p-2 space-y-1.5">
            <p className="text-xs text-gray-600">
              상품을 보내신 뒤 회수 송장을 등록해 주세요. 회수가 확인되어야 다음 단계로 넘어갑니다.
            </p>
            <div className="flex flex-wrap gap-1.5">
              <input
                aria-label="회수 택배사"
                value={waybill.carrier}
                onChange={(e) => setWaybill({ ...waybill, carrier: e.target.value })}
                placeholder="택배사"
                className="border border-gray-300 rounded px-2 py-1 text-xs w-24"
              />
              <input
                aria-label="회수 송장번호"
                value={waybill.trackingNumber}
                onChange={(e) => setWaybill({ ...waybill, trackingNumber: e.target.value })}
                placeholder="송장번호"
                className="border border-gray-300 rounded px-2 py-1 text-xs flex-1 min-w-[8rem]"
              />
              <button
                type="button"
                disabled={busy || waybill.carrier.trim() === '' || waybill.trackingNumber.trim() === ''}
                onClick={() => void saveWaybill()}
                className="px-3 py-1 text-xs font-semibold rounded bg-blue-600 text-white disabled:opacity-40"
              >
                송장 등록
              </button>
            </div>
          </div>
        )}

        {open?.returnTrackingNumber && (
          <p className="text-xs text-gray-500">
            회수 송장 {open.returnCarrier} {open.returnTrackingNumber}
          </p>
        )}
        {open?.exchangeTrackingNumber && (
          <p className="text-xs text-gray-500">
            교환품 송장 {open.exchangeCarrier} {open.exchangeTrackingNumber}
          </p>
        )}
      </div>
    );
  }

  const cancellable = canRequestCancellation(order.status);
  const refundable = canRequestRefund(order.status);
  const exchangeable = canRequestExchange(order.status);
  if (!cancellable && !refundable && !exchangeable) return null;

  const openForm = (type: ReturnRequestType) => {
    setMode(type);
    setReasonCode(REASON_CODES_BY_TYPE[type][0]);
    setReasonDetail('');
  };

  const closeForm = () => {
    setMode('idle');
    setReasonCode('');
    setReasonDetail('');
    setAccount({ bankCode: '', accountNumber: '', holderName: '' });
  };

  const submit = async () => {
    if (mode === 'idle') return;
    setBusy(true);
    try {
      const created = await returnRequestApi.submit(order.id, {
        type: mode,
        reasonCode,
        reasonDetail: reasonDetail.trim() === '' ? null : reasonDetail.trim(),
        // 카드 결제면 서버가 무시한다. 결제 수단을 아는 쪽은 화면이 아니라 서버다.
        refundBankCode: account.bankCode === '' ? null : account.bankCode,
        refundAccountNumber: account.accountNumber.trim() === '' ? null : account.accountNumber.trim(),
        refundAccountHolder: account.holderName.trim() === '' ? null : account.holderName.trim(),
      });
      closeForm();
      await refreshOrder();
      showToast(
        created.awaitsRefundAccount
          ? `${RETURN_REQUEST_TYPE_LABEL[mode]}을(를) 신청했습니다. 환불 계좌를 등록해 주세요.`
          : `${RETURN_REQUEST_TYPE_LABEL[mode]}을(를) 신청했습니다.`,
        'success'
      );
    } catch (err) {
      showToast(errorDetail(err, '신청에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  if (mode !== 'idle') {
    const typeLabel = RETURN_REQUEST_TYPE_LABEL[mode];
    // 코드가 사유를 담고 있으면 자유 입력은 선택이지만, '기타' 는 아무것도 말해 주지 않는다.
    const detailRequired = reasonCode === 'OTHER';
    return (
      <div className="mt-3 pt-3 border-t border-gray-100">
        <label className="block text-xs font-medium text-gray-700 mb-1" htmlFor={`reason-${order.id}`}>
          {typeLabel} 사유
        </label>
        <select
          id={`reason-${order.id}`}
          aria-label={`${typeLabel} 사유`}
          value={reasonCode}
          onChange={(e) => setReasonCode(e.target.value)}
          className="w-full border border-gray-300 rounded px-2 py-1.5 text-sm mb-2"
        >
          {REASON_CODES_BY_TYPE[mode].map((code) => (
            <option key={code} value={code}>{returnReasonLabel(code)}</option>
          ))}
        </select>
        <textarea
          aria-label={`${typeLabel} 사유 상세`}
          value={reasonDetail}
          onChange={(e) => setReasonDetail(e.target.value)}
          rows={2}
          maxLength={500}
          placeholder={detailRequired ? '사유를 적어주세요.' : '더 알려주실 내용이 있으면 적어주세요. (선택)'}
          className="w-full border border-gray-300 rounded px-2 py-1.5 text-sm"
        />
        {mode !== 'EXCHANGE' && (
          <details className="mt-2">
            <summary className="text-xs text-gray-600 cursor-pointer">
              무통장·가상계좌로 결제하셨나요? 환불 계좌 입력 (선택)
            </summary>
            <div className="flex flex-wrap gap-1.5 mt-1.5">
              <select
                aria-label="환불 은행"
                value={account.bankCode}
                onChange={(e) => setAccount({ ...account, bankCode: e.target.value })}
                className="border border-gray-300 rounded px-2 py-1 text-xs"
              >
                <option value="">은행 선택</option>
                {BANK_OPTIONS.map((b) => (
                  <option key={b.code} value={b.code}>{b.name}</option>
                ))}
              </select>
              <input
                aria-label="환불 계좌번호"
                value={account.accountNumber}
                onChange={(e) => setAccount({ ...account, accountNumber: e.target.value })}
                placeholder="계좌번호"
                className="border border-gray-300 rounded px-2 py-1 text-xs flex-1 min-w-[8rem]"
              />
              <input
                aria-label="예금주"
                value={account.holderName}
                onChange={(e) => setAccount({ ...account, holderName: e.target.value })}
                placeholder="예금주"
                className="border border-gray-300 rounded px-2 py-1 text-xs w-24"
              />
            </div>
            {/* 세 칸은 함께 채우거나 함께 비운다 — 반쪽 계좌는 계좌를 안 낸 것이 아니라 오류다. */}
            <p className="text-xs text-gray-400 mt-1">
              비워 두셔도 됩니다. 계좌가 필요한 결제면 신청 후에 다시 여쭤봅니다.
            </p>
          </details>
        )}
        <div className="flex gap-2 mt-2">
          <button
            type="button"
            disabled={busy || reasonCode === '' || (detailRequired && reasonDetail.trim() === '')}
            onClick={() => void submit()}
            className="px-3 py-1.5 text-xs font-semibold rounded bg-blue-600 text-white disabled:opacity-40"
          >
            {typeLabel} 신청
          </button>
          <button
            type="button"
            onClick={closeForm}
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
          onClick={() => openForm('CANCEL')}
          className="px-3 py-1.5 text-xs font-medium rounded border border-gray-300 text-gray-700"
        >
          취소 신청
        </button>
      )}
      {refundable && (
        <button
          type="button"
          onClick={() => openForm('RETURN')}
          className="px-3 py-1.5 text-xs font-medium rounded border border-gray-300 text-gray-700"
        >
          반품 신청
        </button>
      )}
      {exchangeable && (
        <button
          type="button"
          onClick={() => openForm('EXCHANGE')}
          className="px-3 py-1.5 text-xs font-medium rounded border border-gray-300 text-gray-700"
        >
          교환 신청
        </button>
      )}
    </div>
  );
};

export default OrderRequestActions;
