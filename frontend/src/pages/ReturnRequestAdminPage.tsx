import React, { useCallback, useEffect, useState } from 'react';
import {
  adminReturnRequestApi,
  bankLabel,
  returnReasonLabel,
  BANK_OPTIONS,
  RETURN_REQUEST_STATUS_LABEL,
  RETURN_REQUEST_TYPE_LABEL,
  type ReturnRequestResponse,
  type ReturnRequestStatusValue,
} from '@/api/returnRequest';
import Spinner from '@/components/Spinner';
import { apiErrorMessage, errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

/**
 * 반품·교환 처리 콘솔.
 *
 * <p>서버에는 승인·거절·회수·재배송·환불 경로가 이미 다 있었지만 <b>누를 자리가 없었다.</b>
 * 그동안 반품은 주문 승인 큐의 "환불 승인" 버튼 하나로 뭉개졌고, 그 버튼은 물건이 돌아왔는지를
 * 묻지 않는다. 그래서 회수 전에 환불이 나가는 것을 막을 방법이 없었다.
 *
 * <p><b>왜 취소·환불 승인 큐(/admin/approvals)와 따로인가</b>: 그 화면은 주문 상태를 보고
 * 승인 버튼 하나를 누르는 곳이다. 여기는 신청 하나가 승인 → 회수 → 환불/재배송으로 <b>여러 번</b>
 * 지나가고, 단계마다 눌러야 할 것이 다르다. 두 흐름을 한 목록에 섞으면 맞는 버튼이 없는 행이 선다.
 *
 * <p>화면 URL 이 {@code /admin/return-requests} 가 아닌 이유: 그건 이 화면이 부르는 API 경로다.
 * 같은 URL 을 화면에 쓰면 새로고침 때 목록 JSON 이 그대로 브라우저에 뜬다. nginx SPA 폴백이
 * {@code /admin} 아래에서 네비 그룹 접두사만 index.html 로 내려보내므로 승인 그룹 아래에 둔다.
 */

/** 대기열 한 번에 받는 건수 — 서버 기본 상한과 같게. */
const QUEUE_LIMIT = 100;

type Filter = { key: string; label: string; statuses?: readonly ReturnRequestStatusValue[] };

const FILTERS: readonly Filter[] = [
  { key: 'open', label: '처리 대기' },
  { key: 'requested', label: '신청됨', statuses: ['REQUESTED'] },
  { key: 'approved', label: '승인됨', statuses: ['APPROVED'] },
  { key: 'collected', label: '회수 완료', statuses: ['COLLECTED'] },
  { key: 'closed', label: '종료', statuses: ['COMPLETED', 'REJECTED', 'WITHDRAWN'] },
];

const TYPE_CLASS: Record<string, string> = {
  RETURN: 'bg-purple-100 text-purple-800',
  EXCHANGE: 'bg-orange-100 text-orange-800',
  CANCEL: 'bg-gray-200 text-gray-700',
};

const STATUS_CLASS: Record<string, string> = {
  REQUESTED: 'bg-blue-100 text-blue-800',
  APPROVED: 'bg-emerald-100 text-emerald-800',
  COLLECTED: 'bg-teal-100 text-teal-800',
  COMPLETED: 'bg-gray-100 text-gray-600',
  REJECTED: 'bg-red-100 text-red-700',
  WITHDRAWN: 'bg-gray-100 text-gray-500',
};

const fmtDate = (s: string) =>
  new Date(s).toLocaleString('ko-KR', {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });

/* ─────────────────────────────────────────
   신청 1건
───────────────────────────────────────── */
const RequestRow: React.FC<{
  request: ReturnRequestResponse;
  onChanged: (updated: ReturnRequestResponse) => void;
}> = ({ request, onChanged }) => {
  const { showToast } = useToast();
  const [busy, setBusy] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [waybill, setWaybill] = useState({ carrier: '', trackingNumber: '' });
  const [account, setAccount] = useState({ bankCode: '', accountNumber: '', holderName: '' });

  const run = async (
    action: () => Promise<ReturnRequestResponse>, done: string,
  ) => {
    setBusy(true);
    try {
      onChanged(await action());
      showToast(done, 'success');
    } catch (err) {
      showToast(errorDetail(err, '처리에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const { id, status, type } = request;
  const collectsGoods = type !== 'CANCEL';

  // 서버 전이표의 사본. 여기서 잘못 열어도 서버가 막지만, 막힐 버튼을 보여 주는 것 자체가
  // "왜 안 되는지"를 운영자가 눌러 보고서야 알게 만든다.
  const canApprove = status === 'REQUESTED';
  const canReject = status === 'REQUESTED' || status === 'APPROVED' || status === 'COLLECTED';
  const needsWaybill = collectsGoods && status === 'APPROVED' && request.returnTrackingNumber == null;
  const canCollect = collectsGoods && status === 'APPROVED' && request.returnTrackingNumber != null;
  const canShipExchange = type === 'EXCHANGE' && status === 'COLLECTED';
  const canRefund = type !== 'EXCHANGE' && status === 'COLLECTED';

  /**
   * 계좌가 필요한데 비어 있으면 환불 버튼을 잠근다. 서버도 막지만, 여기서 막지 않으면 운영자는
   * 환불을 누르고 실패 토스트를 본 뒤에야 계좌가 없다는 사실을 안다 — 그것도 하루에 수십 번.
   */
  const blockedByAccount = canRefund && request.awaitsRefundAccount;

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-bold text-gray-900">
            신청 #{id} · 주문 #{request.orderId}
          </p>
          <p className="text-xs text-gray-400 mt-0.5">
            사용자 #{request.userId} · {fmtDate(request.requestedAt)}
          </p>
          <p className="text-sm text-gray-700 mt-1">
            {returnReasonLabel(request.reasonCode)}
            {request.reasonDetail && (
              <span className="text-gray-500"> — {request.reasonDetail}</span>
            )}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1 shrink-0">
          <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${TYPE_CLASS[type] ?? ''}`}>
            {RETURN_REQUEST_TYPE_LABEL[type] ?? type}
          </span>
          <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_CLASS[status] ?? ''}`}>
            {RETURN_REQUEST_STATUS_LABEL[status] ?? status}
          </span>
        </div>
      </div>

      {/* 사실 — 눌러 보지 않아도 상태를 알 수 있게 항상 적는다 */}
      <div className="mt-2 text-xs text-gray-500 space-y-0.5">
        {request.refundAccountNumberMasked && (
          <p>
            환불 계좌: {bankLabel(request.refundBankCode)} {request.refundAccountNumberMasked}
            {request.refundAccountHolder && ` (${request.refundAccountHolder})`}
          </p>
        )}
        {request.awaitsRefundAccount && (
          <p className="text-amber-600 font-medium">
            계좌 환불 대상인데 환불받을 계좌가 없습니다 — 등록 전에는 환불이 실행되지 않습니다.
          </p>
        )}
        {request.returnTrackingNumber && (
          <p>회수 송장: {request.returnCarrier} {request.returnTrackingNumber}</p>
        )}
        {request.exchangeTrackingNumber && (
          <p>교환 재배송: {request.exchangeCarrier} {request.exchangeTrackingNumber}</p>
        )}
        {request.rejectReason && <p className="text-red-600">거절 사유: {request.rejectReason}</p>}
        {request.processedBy && <p>처리자: {request.processedBy}</p>}
      </div>

      {/* 단계별 동작 */}
      <div className="mt-3 space-y-2">
        {canApprove && (
          <button
            type="button"
            disabled={busy}
            onClick={() => void run(() => adminReturnRequestApi.approve(id),
              type === 'CANCEL' ? '취소를 승인하고 환불까지 끝냈습니다.' : '승인했습니다. 회수를 기다립니다.')}
            className="px-3 py-1.5 text-sm font-semibold rounded bg-gray-900 text-white disabled:opacity-40"
          >
            {type === 'CANCEL' ? '승인 (환불까지)' : '승인'}
          </button>
        )}

        {needsWaybill && (
          <div className="flex flex-wrap items-center gap-1.5">
            <input
              aria-label="회수 택배사"
              placeholder="회수 택배사"
              value={waybill.carrier}
              onChange={(e) => setWaybill({ ...waybill, carrier: e.target.value })}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm w-28"
            />
            <input
              aria-label="회수 송장번호"
              placeholder="회수 송장번호"
              value={waybill.trackingNumber}
              onChange={(e) => setWaybill({ ...waybill, trackingNumber: e.target.value })}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm w-40"
            />
            <button
              type="button"
              disabled={busy || waybill.carrier.trim() === '' || waybill.trackingNumber.trim() === ''}
              onClick={() => void run(
                () => adminReturnRequestApi.registerReturnWaybill(id, {
                  carrier: waybill.carrier.trim(), trackingNumber: waybill.trackingNumber.trim(),
                }), '회수 송장을 등록했습니다.')}
              className="px-3 py-1.5 text-sm font-semibold rounded border border-gray-300 text-gray-700 disabled:opacity-40"
            >
              회수 송장 대리 등록
            </button>
            <span className="text-xs text-gray-400">고객이 송장을 아직 적지 않았습니다</span>
          </div>
        )}

        {canCollect && (
          <button
            type="button"
            disabled={busy}
            onClick={() => void run(() => adminReturnRequestApi.collect(id), '회수를 확인했습니다.')}
            className="px-3 py-1.5 text-sm font-semibold rounded bg-teal-700 text-white disabled:opacity-40"
          >
            회수 확인
          </button>
        )}

        {canShipExchange && (
          <div className="flex flex-wrap items-center gap-1.5">
            <input
              aria-label="교환 택배사"
              placeholder="교환 택배사"
              value={waybill.carrier}
              onChange={(e) => setWaybill({ ...waybill, carrier: e.target.value })}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm w-28"
            />
            <input
              aria-label="교환 송장번호"
              placeholder="교환 송장번호"
              value={waybill.trackingNumber}
              onChange={(e) => setWaybill({ ...waybill, trackingNumber: e.target.value })}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm w-40"
            />
            <button
              type="button"
              disabled={busy || waybill.carrier.trim() === '' || waybill.trackingNumber.trim() === ''}
              onClick={() => void run(
                () => adminReturnRequestApi.shipExchange(id, {
                  carrier: waybill.carrier.trim(), trackingNumber: waybill.trackingNumber.trim(),
                }), '교환품을 재배송 처리했습니다.')}
              className="px-3 py-1.5 text-sm font-semibold rounded bg-orange-600 text-white disabled:opacity-40"
            >
              교환품 재배송
            </button>
          </div>
        )}

        {blockedByAccount && (
          <div className="flex flex-wrap items-center gap-1.5">
            <select
              aria-label="환불 은행"
              value={account.bankCode}
              onChange={(e) => setAccount({ ...account, bankCode: e.target.value })}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm"
            >
              <option value="">은행 선택</option>
              {BANK_OPTIONS.map((b) => (
                <option key={b.code} value={b.code}>{b.name}</option>
              ))}
            </select>
            <input
              aria-label="환불 계좌번호"
              placeholder="계좌번호"
              value={account.accountNumber}
              onChange={(e) => setAccount({ ...account, accountNumber: e.target.value })}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm w-40"
            />
            <input
              aria-label="예금주"
              placeholder="예금주"
              value={account.holderName}
              onChange={(e) => setAccount({ ...account, holderName: e.target.value })}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm w-24"
            />
            <button
              type="button"
              disabled={busy || account.bankCode === '' || account.accountNumber.trim() === ''
                || account.holderName.trim() === ''}
              onClick={() => void run(
                () => adminReturnRequestApi.changeRefundAccount(id, {
                  bankCode: account.bankCode,
                  accountNumber: account.accountNumber.trim(),
                  holderName: account.holderName.trim(),
                }), '환불 계좌를 등록했습니다.')}
              className="px-3 py-1.5 text-sm font-semibold rounded border border-gray-300 text-gray-700 disabled:opacity-40"
            >
              계좌 등록
            </button>
          </div>
        )}

        {canRefund && (
          <button
            type="button"
            disabled={busy || blockedByAccount}
            onClick={() => void run(() => adminReturnRequestApi.refund(id), '환불을 실행했습니다.')}
            className="px-3 py-1.5 text-sm font-semibold rounded bg-purple-700 text-white disabled:opacity-40"
          >
            환불 실행
          </button>
        )}

        {canReject && (
          <div className="flex flex-wrap items-center gap-1.5">
            <input
              aria-label="거절 사유"
              placeholder="거절 사유"
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              className="flex-1 min-w-[12rem] border border-gray-300 rounded px-2 py-1.5 text-sm"
            />
            <button
              type="button"
              // 사유 없는 거절은 고객에게 "안 됩니다" 한 줄로 도착한다. 서버는 빈 사유도 받지만
              // 그 관용을 화면까지 내리면 사유 없는 거절이 기본값이 된다.
              disabled={busy || rejectReason.trim() === ''}
              onClick={() => void run(
                () => adminReturnRequestApi.reject(id, rejectReason.trim()), '신청을 거절했습니다.')}
              className="px-3 py-1.5 text-sm font-semibold rounded border border-red-300 text-red-700 disabled:opacity-40"
            >
              거절
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

/* ─────────────────────────────────────────
   대기열
───────────────────────────────────────── */
const ReturnRequestAdminPage: React.FC = () => {
  const [filter, setFilter] = useState<Filter>(FILTERS[0]);
  const [requests, setRequests] = useState<ReturnRequestResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (target: Filter) => {
    setLoading(true);
    setError(null);
    try {
      setRequests(await adminReturnRequestApi.queue(target.statuses, QUEUE_LIMIT));
    } catch (err) {
      // 목록 조회는 apiErrorMessage 다 — 서버가 준 문구가 없으면 "Network Error" 같은 원문 대신
      // 화면용 문장으로 떨어진다. 실패를 0건으로 뭉개지만 않으면 되고, 원인 문구까지 필요한 자리는
      // 아니다(동작 버튼 쪽은 서버 문구가 곧 사유라 errorDetail 을 쓴다).
      setError(apiErrorMessage(err, '신청 목록을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(filter);
  }, [load, filter]);

  /**
   * 바뀐 행을 그 자리에서 갈아끼우고 목록에서 빼지 않는다. 처리 직후 사라지면 운영자는 방금
   * 무엇을 눌렀는지 확인할 수 없고, 잘못 누른 경우 되돌릴 실마리도 함께 사라진다.
   */
  const handleChanged = (updated: ReturnRequestResponse) =>
    setRequests((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto">
        <div className="flex items-center justify-between mb-5">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">반품·교환 처리</h1>
            <p className="text-sm text-gray-500 mt-1">
              승인 → 회수 확인 → 환불 또는 교환 재배송. 물건이 돌아온 뒤에야 돈이 움직입니다.
            </p>
          </div>
          <button
            onClick={() => void load(filter)}
            className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 text-gray-700 bg-white"
          >
            새로고침
          </button>
        </div>

        <div className="flex flex-wrap gap-1.5 mb-4">
          {FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              onClick={() => setFilter(f)}
              className={`px-3 py-1.5 text-sm font-medium rounded border ${
                filter.key === f.key
                  ? 'bg-gray-900 text-white border-gray-900'
                  : 'bg-white text-gray-700 border-gray-300'
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>

        {loading ? (
          <Spinner size="md" message="신청 목록 불러오는 중..." />
        ) : error ? (
          <p className="text-center text-red-600 py-8">{error}</p>
        ) : requests.length === 0 ? (
          <div className="text-center py-16 text-gray-400 bg-white rounded-xl border border-gray-200">
            <p className="text-sm">해당하는 반품·교환 신청이 없습니다.</p>
          </div>
        ) : (
          <div className="space-y-3">
            <p className="text-sm text-gray-500">
              {requests.length.toLocaleString()}건
              {requests.length >= QUEUE_LIMIT && (
                // 상한에 닿았다는 것은 더 있을 수 있다는 뜻이다. 감추면 보이는 것이 전부라고 믿는다.
                <span className="text-amber-600"> · 상한({QUEUE_LIMIT})에 닿았습니다 — 더 있을 수 있습니다</span>
              )}
            </p>
            {requests.map((r) => (
              <RequestRow key={r.id} request={r} onChanged={handleChanged} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default ReturnRequestAdminPage;
