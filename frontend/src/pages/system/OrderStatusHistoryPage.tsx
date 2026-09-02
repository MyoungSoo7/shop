import React, { useState } from 'react';
import { orderStatusHistoryApi, type OrderStatusTimeline } from '@/api/orderStatusHistory';
import { apiErrorMessage, apiErrorStatus } from '@/lib/apiError';
import { humanizeDwell, LONG_DWELL_SECONDS } from '@/lib/dwell';

/**
 * 주문 상태 이력 조회 — CS 의 "이 주문 왜 이 상태예요?" 를 한 번의 조회로 끝낸다.
 *
 * <p>그전까지 이 질문의 답은 운영 DB 의 {@code order_status_history} 를 손으로 조회하는 것뿐이었다.
 * 그건 CS 에게 DB 접근 권한을 주거나 개발자가 매번 대신 조회하거나 둘 중 하나를 뜻했다.
 *
 * <p><b>표를 그리는 것이 목적이 아니다.</b> 행을 나열하는 것만으로는 정작 필요한 두 가지가 안 보인다:
 * <ol>
 *   <li><b>어디서 오래 멈췄나</b> — 각 상태의 체류 시간. 마지막 칸은 "지금 몇 초째" 다.
 *   <li><b>이력을 안 남기고 바뀐 전이가 있나</b> — 이력의 마지막 도착 상태와 주문의 현재 상태가
 *       다르면, 그 사이를 기록 없이 건너뛴 경로가 있다는 뜻이다. 표만 보면 이건 절대 안 보인다.
 * </ol>
 *
 * <p>없는 주문과 이력이 0건인 주문을 <b>다르게</b> 보여 준다. 서버가 앞쪽을 404 로 구분해 주므로
 * 화면도 뭉개지 않는다 — 전자는 주문번호 오타이고 후자는 조사해야 할 상태다.
 *
 * <p>상태값을 한국어로 번역하지 않는다. 이력에는 지금 코드가 모르는 옛 상태값이 들어 있고
 * (폐기된 값의 행이 실제로 남아 있다), 사전에 없는 값을 만나면 표시가 비거나 거짓말이 된다.
 * 원문 그대로 보여 주는 편이 이력이라는 자료의 성격에 맞는다.
 */

const fmtDateTime = (s: string) =>
  new Date(s).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'medium' });

const OrderStatusHistoryPage: React.FC = () => {
  const [input, setInput] = useState('');
  const [timeline, setTimeline] = useState<OrderStatusTimeline | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [loading, setLoading] = useState(false);

  const lookup = async () => {
    const orderId = Number(input.trim());
    if (!Number.isInteger(orderId) || orderId <= 0) {
      setError('주문번호는 양의 정수입니다.');
      setTimeline(null);
      setNotFound(false);
      return;
    }
    setLoading(true);
    setError(null);
    setNotFound(false);
    setTimeline(null);
    try {
      setTimeline(await orderStatusHistoryApi.of(orderId));
    } catch (err) {
      // 404 는 "없는 주문" 이다. 오류 문구로 뭉개면 오타와 조사할 버그가 같아 보인다.
      if (apiErrorStatus(err) === 404) setNotFound(true);
      else setError(apiErrorMessage(err, '상태 이력을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">주문 상태 이력</h1>
          <p className="text-sm text-gray-500 mt-1">
            주문 한 건의 상태 전이를 시간순으로, <b>각 상태에 머문 시간</b>과 함께 봅니다.
          </p>
        </div>

        <form className="flex items-end gap-2"
          onSubmit={(e) => { e.preventDefault(); void lookup(); }}>
          <label className="text-sm">
            <span className="block text-gray-600">주문번호</span>
            <input value={input} onChange={(e) => setInput(e.target.value)}
              inputMode="numeric" aria-label="주문번호" placeholder="예: 4242"
              className="mt-1 rounded border border-gray-300 px-2 py-1.5" />
          </label>
          <button type="submit" disabled={loading || input.trim() === ''}
            className="rounded bg-gray-900 px-3 py-2 text-sm font-semibold text-white disabled:opacity-40">
            {loading ? '조회 중…' : '조회'}
          </button>
        </form>

        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

        {notFound && (
          <p className="rounded bg-gray-100 p-3 text-sm text-gray-700" data-testid="order-not-found">
            그런 주문이 없습니다. 주문번호를 확인하세요 —{' '}
            <b>이력이 비어 있는 것과는 다른 결과입니다.</b>
          </p>
        )}

        {timeline && (
          <>
            <div className="rounded border border-gray-200 bg-white p-4 space-y-2">
              <p className="text-sm">
                주문 <b className="font-mono" data-testid="order-id">{timeline.orderId}</b> ·
                {' '}현재 상태{' '}
                <b className="font-mono" data-testid="current-status">{timeline.currentStatus ?? '없음'}</b>
                {' '}· 이력 {timeline.steps.length}건
              </p>

              {/* 이 한 줄이 표 덤프와의 차이다. */}
              <p className={`rounded p-3 text-sm ${timeline.historyMatchesOrder ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'}`}
                data-testid="match-summary">
                {timeline.historyMatchesOrder ? (
                  <>이력의 마지막 도착 상태가 주문의 현재 상태와 일치합니다.</>
                ) : timeline.lastRecordedStatus === null ? (
                  <>
                    <b>이력이 한 건도 없습니다.</b> 주문은 {timeline.currentStatus ?? '알 수 없음'} 상태인데
                    거기까지 온 기록이 없습니다 — 기록을 남기지 않는 전이 경로가 있다는 뜻입니다.
                  </>
                ) : (
                  <>
                    <b>이력과 주문 상태가 어긋납니다.</b> 이력의 마지막 도착 상태는{' '}
                    <span className="font-mono" data-testid="last-recorded">{timeline.lastRecordedStatus}</span>
                    {' '}인데 주문은{' '}
                    <span className="font-mono">{timeline.currentStatus ?? '알 수 없음'}</span>
                    {' '}입니다 — 그 사이를 기록 없이 건너뛴 경로가 있습니다.
                  </>
                )}
              </p>
            </div>

            {timeline.steps.length === 0 ? (
              <p className="text-sm text-gray-600" data-testid="timeline-empty">
                표시할 상태 전이가 없습니다.
              </p>
            ) : (
              <table className="w-full text-sm" data-testid="timeline-table">
                <thead className="text-left text-gray-500">
                  <tr>
                    <th className="py-2">#</th><th>이전</th><th>이후</th>
                    <th>머문 시간</th><th>바꾼 주체</th><th>사유</th><th>시각</th>
                  </tr>
                </thead>
                <tbody>
                  {timeline.steps.map((s, i) => {
                    const last = i === timeline.steps.length - 1;
                    return (
                      <tr key={s.id} className={`border-t ${s.dwellSeconds >= LONG_DWELL_SECONDS ? 'bg-amber-50' : ''}`}
                        data-testid={`step-${s.id}`}>
                        <td className="py-2">{i + 1}</td>
                        <td className="font-mono text-gray-500">{s.previousStatus ?? '—'}</td>
                        <td className="font-mono font-semibold">{s.newStatus}</td>
                        <td data-testid={`dwell-${s.id}`}>
                          {humanizeDwell(s.dwellSeconds)}
                          {/* 마지막 칸은 "지금 몇 초째" 라 성격이 다르다 — 못박아 둔다. */}
                          {last && <span className="ml-1 text-xs text-gray-500">(현재 진행 중)</span>}
                        </td>
                        <td className="text-gray-700">{s.changedBy ?? '-'}</td>
                        <td className="max-w-xs truncate text-gray-700" title={s.reason ?? ''}>
                          {s.reason ?? '-'}
                        </td>
                        <td className="text-xs text-gray-500">{fmtDateTime(s.changedAt)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}

            <p className="rounded bg-gray-100 p-3 text-xs text-gray-600">
              상태값은 기록된 원문 그대로입니다. 지금 코드가 쓰지 않는 옛 상태값이 남아 있을 수 있고,
              그것을 지우거나 번역하면 이력이 아니라 재해석이 됩니다.
            </p>
          </>
        )}
      </div>
    </div>
  );
};

export default OrderStatusHistoryPage;
