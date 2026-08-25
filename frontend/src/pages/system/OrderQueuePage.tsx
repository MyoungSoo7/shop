import React, { useCallback, useEffect, useState } from 'react';
import { orderQueueApi, totalCount, totalOverdue, type OrderQueues } from '@/api/orderQueue';
import { saveBlob } from '@/api/auditLog';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 작업 큐 — 주문을 <b>운영자가 해야 할 일</b> 단위로 묶고 기한을 매긴다.
 *
 * <p>주문 목록과 다르다. 상태별 카운트는 "환불 신청 3건"이라고만 말하는데, 오늘 들어온 3건과
 * 나흘 묵은 3건은 전혀 다른 상황이다. 그래서 이 화면은 건수보다 <b>기한 초과</b>와
 * <b>최장 대기</b>를 먼저 보여 준다.
 *
 * <p><b>순서는 서버가 정한다.</b> 돈이 묶여 있고 되돌리기 어려운 것부터 위다. 화면이 건수
 * 내림차순 같은 것으로 다시 정렬하면, 건수는 적지만 급한 큐(취소 승인 후 미완료)가 아래로 밀린다.
 *
 * <p><b>{@code ageFromOrderDateCount} 를 표시하는 이유</b>: 대기 시작 시각은 상태 변경 이력에서
 * 읽는데, 이력이 없는 옛 주문은 주문 일시로 대신 잰다. 그 건들의 대기 시간은 실제보다 길게
 * 보인다. 서버는 그 대체가 정확한 큐(미결제)에서는 이 값을 0 으로 주므로, 화면은 0 이 아닌
 * 큐에만 배지를 붙인다 — 늘 켜져 있는 경고는 아무도 읽지 않는다.
 */

const fmtNumber = (v: number) => new Intl.NumberFormat('ko-KR').format(v);

const fmtDate = (s: string | null) =>
  s ? new Date(s).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }) : '-';

/** 시간 → "3일 5시간". 168시간을 그대로 찍으면 얼마나 오래인지 읽히지 않는다. */
const fmtWaiting = (hours: number | null) => {
  if (hours === null) return '-';
  const days = Math.floor(hours / 24);
  const rest = hours % 24;
  return days > 0 ? `${days}일 ${rest}시간` : `${rest}시간`;
};

const OrderQueuePage: React.FC = () => {
  const [queues, setQueues] = useState<OrderQueues | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setQueues(await orderQueueApi.list());
    } catch (err) {
      // 빈 표를 그리면 조회 실패가 "밀린 일 0건"으로 위장한다 — 이 화면에서 가장 나쁜 오해다.
      setQueues(null);
      setError(apiErrorMessage(err, '작업 큐를 불러오지 못했습니다.'));
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const download = async () => {
    setError(null);
    try {
      const { blob, fileName, asOf } = await orderQueueApi.export();
      saveBlob(blob, fileName);
      // 큐는 계속 움직인다 — 파일만 보고는 어느 시점인지 알 수 없어 기준 시각을 같이 말한다.
      setNotice(`내려받았습니다 — 기준 시각 ${asOf === null ? '알 수 없음' : fmtDate(asOf)}.`);
    } catch (err) {
      setError(apiErrorMessage(err, 'CSV 를 내려받지 못했습니다.'));
    }
  };

  const overdue = queues ? totalOverdue(queues) : 0;

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-5xl mx-auto space-y-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">작업 큐</h1>
            <p className="text-sm text-gray-500 mt-1">
              밀린 주문을 <b>해야 할 일</b> 단위로 묶었습니다. 건수보다 기한 초과와 최장 대기를
              먼저 보세요.
            </p>
          </div>
          <div className="flex shrink-0 gap-2">
            <button type="button" onClick={() => void load()}
              className="rounded border border-gray-300 bg-white px-3 py-2 text-sm font-semibold text-gray-700">
              새로고침
            </button>
            <button type="button" onClick={() => void download()}
              className="rounded border border-gray-300 bg-white px-3 py-2 text-sm font-semibold text-gray-700">
              CSV
            </button>
          </div>
        </div>

        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        {notice && <p className="text-sm text-green-700" data-testid="queue-notice">{notice}</p>}

        {queues === null ? (
          !error && <p className="text-sm text-gray-500">불러오는 중…</p>
        ) : (
          <>
            <p className={`rounded p-3 text-sm ${overdue > 0 ? 'bg-red-50 text-red-800' : 'bg-green-50 text-green-800'}`}
              data-testid="queue-summary">
              {overdue > 0
                ? <>기한을 넘긴 <b>{fmtNumber(overdue)}건</b>이 있습니다 — 밀린 일 전체는 {fmtNumber(totalCount(queues))}건입니다.</>
                : <>기한을 넘긴 건이 없습니다 — 밀린 일 {fmtNumber(totalCount(queues))}건은 모두 기한 안입니다.</>}
            </p>
            <p className="text-xs text-gray-500" data-testid="queue-as-of">
              기준 시각 {fmtDate(queues.asOf)} · 대기 시간은 모두 이 시각에서 뺀 값입니다.
            </p>

            <table className="w-full text-sm" data-testid="queue-table">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">큐</th>
                  <th className="text-right">건수</th>
                  <th className="text-right">기한 초과</th>
                  <th>최장 대기</th>
                  <th>기한</th>
                </tr>
              </thead>
              <tbody>
                {/* 서버가 준 순서를 그대로 쓴다 — 돈이 묶여 있고 되돌리기 어려운 것부터다. */}
                {queues.buckets.map((bucket) => (
                  <tr key={bucket.key} className={`border-t ${bucket.overdueCount > 0 ? 'bg-red-50' : ''}`}
                    data-testid={`queue-row-${bucket.key}`}>
                    <td className="py-2">
                      <div className="font-medium text-gray-900">{bucket.label}</div>
                      <div className="text-xs text-gray-500">{bucket.statuses.join(' · ')}</div>
                      {bucket.ageFromOrderDateCount > 0 && (
                        <span className="mt-1 inline-block rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800"
                          data-testid={`estimated-${bucket.key}`}>
                          {fmtNumber(bucket.ageFromOrderDateCount)}건은 이력이 없어 주문 일시로 쟀습니다 — 대기 시간이 과대평가됩니다
                        </span>
                      )}
                    </td>
                    <td className="text-right">{fmtNumber(bucket.count)}</td>
                    <td className="text-right" data-testid={`overdue-${bucket.key}`}>
                      {bucket.overdueCount > 0
                        ? <b className="text-red-700">{fmtNumber(bucket.overdueCount)}</b>
                        : <span className="text-gray-400">0</span>}
                    </td>
                    <td data-testid={`waiting-${bucket.key}`}>
                      {fmtWaiting(bucket.oldestWaitingHours)}
                      {bucket.oldestWaitingSince && (
                        <div className="text-xs text-gray-500">{fmtDate(bucket.oldestWaitingSince)}부터</div>
                      )}
                    </td>
                    <td className="text-xs text-gray-500">{fmtWaiting(bucket.slaHours)}</td>
                  </tr>
                ))}
              </tbody>
            </table>

            <p className="rounded bg-gray-100 p-3 text-xs text-gray-600">
              이 화면에는 조작 버튼이 없습니다. 각 큐의 처리는 주문·환불 화면에서 건별로 합니다 —
              목록에서 일괄 처리하면 어떤 건을 무슨 근거로 처리했는지가 남지 않습니다.
            </p>
          </>
        )}
      </div>
    </div>
  );
};

export default OrderQueuePage;
