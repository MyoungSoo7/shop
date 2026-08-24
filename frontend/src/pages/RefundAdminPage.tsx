import React, { useCallback, useEffect, useState } from 'react';
import {
  refundAdminApi,
  REFUND_MAX_RETRIES,
  type AdminRefundItem,
  type RefundHistory,
  type RefundStatus,
} from '@/api/refundAdmin';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 환불 운영 콘솔 — 실패한 환불 중 <b>사람이 손대야 하는 것</b>을 골라 준다.
 *
 * <p><b>핵심은 FAILED 를 둘로 가르는 것이다.</b> 같은 FAILED 라도
 * {@code retryExhausted=false} 는 스케줄러가 곧 다시 시도할 건이고(백오프 1·5·15·60·180분),
 * {@code true} 는 상한 5회를 다 쓰고 {@code nextRetryAt} 이 비워져 <b>아무도 다시 시도하지 않는</b>
 * 건이다. 목록을 한 덩어리로 보여 주면 운영자가 "곧 알아서 될 것"과 "지금 내가 안 하면 영영 안 될 것"을
 * 구분할 수 없다 — 이 화면이 존재하는 이유가 그 구분이다.
 *
 * <p><b>조작 버튼이 없다.</b> 서버에 운영자용 재시도 API 가 없다. 고객용 환불 요청을 버튼으로
 * 재활용하면 멱등 키 의미가 어긋나 이중 환불이 될 수 있어, 화면이 없는 동작을 지어내지 않는다.
 * 이 화면의 일은 <b>대상을 정확히 골라 주는 것</b>까지다.
 */

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const fmtDate = (s: string | null) =>
  s ? new Date(s).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }) : '-';

const TABS: { value: RefundStatus; label: string; note: string }[] = [
  { value: 'FAILED', label: '실패', note: '자동 재시도가 남았는지로 나뉩니다.' },
  { value: 'REQUESTED', label: '요청됨', note: '아직 결과가 오지 않은 건입니다.' },
  { value: 'COMPLETED', label: '완료', note: '실제로 환불된 건입니다.' },
];

const RefundAdminPage: React.FC = () => {
  const [status, setStatus] = useState<RefundStatus>('FAILED');
  const [rows, setRows] = useState<AdminRefundItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [openPayment, setOpenPayment] = useState<number | null>(null);
  const [history, setHistory] = useState<RefundHistory | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setRows(await refundAdminApi.byStatus(status));
    } catch (err) {
      // 빈 표를 그리면 조회 실패가 "환불 실패 0건"으로 위장한다 — 그 둘을 뭉개지 않는다.
      setRows(null);
      setError(apiErrorMessage(err, '환불 목록을 불러오지 못했습니다.'));
    }
  }, [status]);

  useEffect(() => { void load(); }, [load]);

  /** 탭을 바꾸면 펼쳐 둔 이력을 닫는다 — 다른 목록 위에 남으면 어느 건의 이력인지 모른다. */
  const changeStatus = (next: RefundStatus) => {
    setStatus(next);
    setOpenPayment(null);
    setHistory(null);
    setHistoryError(null);
  };

  const toggleHistory = async (paymentId: number) => {
    if (openPayment === paymentId) {
      setOpenPayment(null);
      setHistory(null);
      return;
    }
    setOpenPayment(paymentId);
    setHistory(null);
    setHistoryError(null);
    try {
      setHistory(await refundAdminApi.historyOf(paymentId));
    } catch (err) {
      setHistoryError(apiErrorMessage(err, '환불 이력을 불러오지 못했습니다.'));
    }
  };

  const needsHuman = (rows ?? []).filter((r) => r.retryExhausted).length;
  const tab = TABS.find((t) => t.value === status)!;

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-5xl mx-auto space-y-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">환불 운영</h1>
            <p className="text-sm text-gray-500 mt-1">
              자동 재시도는 {REFUND_MAX_RETRIES}회까지입니다. 그 뒤로는 스케줄러가 손대지 않으니
              <b> 사람이 하지 않으면 영영 처리되지 않습니다.</b>
            </p>
          </div>
          <button type="button" onClick={() => void load()}
            className="shrink-0 rounded border border-gray-300 bg-white px-3 py-2 text-sm font-semibold text-gray-700">
            새로고침
          </button>
        </div>

        <div className="flex gap-2" role="tablist">
          {TABS.map((t) => (
            <button key={t.value} type="button" role="tab" aria-selected={status === t.value}
              onClick={() => changeStatus(t.value)}
              className={`rounded px-3 py-1.5 text-sm font-semibold ${
                status === t.value ? 'bg-gray-900 text-white' : 'border border-gray-300 bg-white text-gray-700'}`}>
              {t.label}
            </button>
          ))}
        </div>
        <p className="text-xs text-gray-500">{tab.note}</p>

        {status === 'FAILED' && rows !== null && (
          <p className={`rounded p-3 text-sm ${needsHuman > 0 ? 'bg-red-50 text-red-800' : 'bg-green-50 text-green-800'}`}
            data-testid="needs-human">
            {needsHuman > 0
              ? <>재시도가 끝난 <b>{needsHuman}건</b>이 사람을 기다립니다 — 나머지는 자동으로 다시 시도됩니다.</>
              : <>재시도가 끝난 건이 없습니다. 실패 건은 모두 자동 재시도가 남아 있습니다.</>}
          </p>
        )}

        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

        {rows === null ? (
          !error && <p className="text-sm text-gray-500">불러오는 중…</p>
        ) : rows.length === 0 ? (
          <p className="text-sm text-gray-600" data-testid="refund-empty">
            {tab.label} 상태의 환불이 없습니다.
          </p>
        ) : (
          <table className="w-full text-sm" data-testid="refund-table">
            <thead className="text-left text-gray-500">
              <tr>
                <th className="py-2">#</th><th>결제</th><th className="text-right">금액</th>
                <th>재시도</th><th>다음 시도</th><th>사유</th><th>요청</th><th />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <React.Fragment key={r.id}>
                  <tr className={`border-t ${r.retryExhausted ? 'bg-red-50' : ''}`}
                    data-testid={`refund-row-${r.id}`}>
                    <td className="py-2">{r.id}</td>
                    <td className="font-mono">{r.paymentId}</td>
                    <td className="text-right">{fmt(r.amount)}</td>
                    <td>
                      {r.retryCount}/{REFUND_MAX_RETRIES}
                      {/* 소진 여부가 이 화면의 전부다 — 배지로 못박는다. */}
                      {r.retryExhausted && (
                        <span className="ml-2 rounded bg-red-600 px-2 py-0.5 text-xs text-white"
                          data-testid={`exhausted-${r.id}`}>
                          재시도 끝
                        </span>
                      )}
                    </td>
                    <td className="text-xs text-gray-500" data-testid={`next-retry-${r.id}`}>
                      {r.retryExhausted ? '없음 — 자동 재시도 안 함' : fmtDate(r.nextRetryAt)}
                    </td>
                    <td className="max-w-xs truncate text-gray-700" title={r.reason ?? ''}>
                      {r.reason ?? '-'}
                    </td>
                    <td className="text-xs text-gray-500">{fmtDate(r.requestedAt)}</td>
                    <td className="text-right">
                      <button type="button" onClick={() => void toggleHistory(r.paymentId)}
                        aria-expanded={openPayment === r.paymentId}
                        className="rounded border border-gray-300 bg-white px-2 py-1 text-xs font-semibold text-gray-700">
                        {openPayment === r.paymentId ? '이력 닫기' : '이력'}
                      </button>
                    </td>
                  </tr>

                  {openPayment === r.paymentId && (
                    <tr className="border-t bg-gray-50" data-testid={`history-${r.paymentId}`}>
                      <td colSpan={8} className="p-3">
                        {historyError && <p role="alert" className="text-sm text-red-600">{historyError}</p>}
                        {!historyError && history === null && (
                          <p className="text-sm text-gray-500">이력을 불러오는 중…</p>
                        )}
                        {history && (
                          <div className="space-y-2">
                            {/* 시도 횟수가 아니라 "실제로 나간 돈"을 먼저 보여 준다 —
                                여러 번 시도한 건에서 이중 환불 여부를 판단하는 기준이다. */}
                            <p className="text-sm">
                              결제 {history.paymentId} · 실제 환불 완료액{' '}
                              <b data-testid="total-refunded">{fmt(history.totalRefunded)}</b>
                              {' '}· 시도 {history.refunds.length}건
                            </p>
                            <ul className="space-y-1 text-xs text-gray-600">
                              {history.refunds.map((h) => (
                                <li key={h.id}>
                                  #{h.id} · {fmt(h.amount)} · {h.status} · {fmtDate(h.requestedAt)}
                                  {h.reason && <> · {h.reason}</>}
                                </li>
                              ))}
                            </ul>
                          </div>
                        )}
                      </td>
                    </tr>
                  )}
                </React.Fragment>
              ))}
            </tbody>
          </table>
        )}

        <p className="rounded bg-gray-100 p-3 text-xs text-gray-600">
          이 화면에는 조작 버튼이 없습니다. 서버에 운영자용 재시도 API 가 없고, 고객용 환불 요청을
          버튼으로 재활용하면 멱등 키 의미가 어긋나 이중 환불이 될 수 있습니다. 재시도가 끝난 건은
          PG 사와 직접 확인해 처리하세요.
        </p>
      </div>
    </div>
  );
};

export default RefundAdminPage;
