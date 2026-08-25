import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  notificationDispatchApi,
  DISPATCH_STATUS_BADGE,
  DISPATCH_STATUS_LABEL,
  type DispatchStatus,
  type NotificationDispatch,
  type NotificationDispatchDetail,
} from '@/api/operation';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 알림 발송 이력 — "그 사람한테 알림이 갔나?" 에 답하는 화면.
 *
 * <p>이 구획이 생기기 전에는 발송 결과가 `log.info` 한 줄이었다. 파드가 재시작하면 흔적이
 * 사라지고, 조회할 방법이 없어 <b>안 간 것과 갔는데 모르는 것을 구분할 수 없었다</b>.
 *
 * <p><b>기본 필터가 "미완결"이다.</b> 전체 목록을 먼저 보여주면 대부분을 차지하는 성공 건이
 * 실패를 덮는다. 운영자가 이 화면을 여는 이유는 잘 간 것을 확인하기 위해서가 아니다.
 *
 * <p><b>재발송은 원본을 고치지 않는다.</b> 새 행이 생기고 원본은 실패한 채로 남는다 —
 * 사고 조사 때 "그때 실패했다"가 지워지면 안 되기 때문이다.
 */

/** 미완결 = 사람이 봐야 하는 상태들. 서버의 부분 인덱스 조건과 같은 집합이다. */
const UNHEALTHY: DispatchStatus[] = ['PENDING', 'PARTIAL', 'FAILED', 'NO_CHANNEL'];
const PAGE_SIZE = 20;

const fmt = (iso: string | null): string => {
  if (!iso) return '-';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '-' : d.toLocaleString('ko-KR', { hour12: false });
};

const NotificationDispatchPanel: React.FC = () => {
  // '' = 전체. 초기값을 비워 두지 않는 이유는 위 javadoc 참조.
  const [status, setStatus] = useState<DispatchStatus | 'UNHEALTHY' | ''>('UNHEALTHY');
  const [recipient, setRecipient] = useState('');
  const [applied, setApplied] = useState('');
  const [offset, setOffset] = useState(0);

  const [rows, setRows] = useState<NotificationDispatch[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [detail, setDetail] = useState<NotificationDispatchDetail | null>(null);
  const [resending, setResending] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  /**
   * 서버는 상태 <b>정확일치</b> 하나만 받는다(인덱스를 타야 해서다). "미완결"은 상태 4개라
   * 화면이 4번 조회해 합친다 — 목록이 짧은 운영 화면이라 이 정도 왕복이 서버에 OR 필터를
   * 얹는 것보다 싸고, 무엇보다 서버 계약이 단순하게 남는다.
   */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const target = applied.trim() || undefined;
      if (status === 'UNHEALTHY') {
        const pages = await Promise.all(
          UNHEALTHY.map((s) => notificationDispatchApi.search({
            status: s, recipient: target, limit: PAGE_SIZE, offset: 0,
          })),
        );
        const merged = pages.flatMap((p) => p.items)
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
          .slice(0, PAGE_SIZE);
        setRows(merged);
        setTotal(pages.reduce((sum, p) => sum + p.total, 0));
        setOffset(0);
      } else {
        const page = await notificationDispatchApi.search({
          status: status || undefined, recipient: target, limit: PAGE_SIZE, offset,
        });
        setRows(page.items);
        setTotal(page.total);
      }
    } catch (err) {
      // 조회 실패를 빈 목록으로 그리면 "발송 이력이 없다"는 정반대 신호가 된다.
      setRows([]);
      setError(apiErrorMessage(err, '발송 이력을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [status, applied, offset]);

  useEffect(() => { void load(); }, [load]);

  const openDetail = async (id: number) => {
    setNotice(null);
    try {
      setDetail(await notificationDispatchApi.get(id));
    } catch (err) {
      setError(apiErrorMessage(err, '상세를 불러오지 못했습니다.'));
    }
  };

  /**
   * 멱등 키를 화면이 만든다. 버튼을 두 번 눌러도 <b>같은 키</b>가 가야 두 번 나가지 않으므로,
   * 대상 id 마다 한 번만 만들어 두고 재사용한다(누를 때 만들면 매번 다른 키가 된다).
   */
  const resendKeys = useMemo(() => new Map<number, string>(), []);
  const keyFor = (id: number): string => {
    const existing = resendKeys.get(id);
    if (existing) return existing;
    const fresh = `console-${id}-${Date.now()}`;
    resendKeys.set(id, fresh);
    return fresh;
  };

  const resend = async (id: number) => {
    setResending(id);
    setNotice(null);
    setError(null);
    try {
      const result = await notificationDispatchApi.resend(id, keyFor(id));
      setNotice(result.deduped
        ? '이미 같은 재발송이 나갔습니다 — 중복 발송되지 않았습니다.'
        : result.allSucceeded
          ? '재발송했습니다 — 활성 채널 전건 성공.'
          : '재발송했으나 일부 채널이 실패했습니다. 상세에서 사유를 확인하세요.');
      await load();
      if (detail?.id === id) await openDetail(id);
    } catch (err) {
      setError(apiErrorMessage(err, '재발송에 실패했습니다.'));
    } finally {
      setResending(null);
    }
  };

  const canPage = status !== 'UNHEALTHY';

  return (
    <section className="rounded-xl border border-gray-200 bg-white p-4 mb-6 space-y-3"
      data-testid="notification-dispatch-panel">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="font-semibold text-gray-900">알림 발송 이력</h2>
          <p className="mt-1 text-sm text-gray-500">
            일부 성공은 실패가 아닙니다 — 한 채널이라도 닿았다는 뜻입니다.
            채널 없음은 메시지가 아니라 배포 설정 문제이고, 미완결은 발송 도중 프로세스가
            죽은 흔적이라 <b>사람이 닫지 않으면 그대로 남습니다</b>.
          </p>
        </div>
        <button type="button" onClick={() => void load()} disabled={loading}
          className="shrink-0 rounded border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 disabled:opacity-50">
          {loading ? '조회 중…' : '발송 이력 새로고침'}
        </button>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm text-gray-600">
          <span className="block text-xs text-gray-500">상태</span>
          <select value={status}
            onChange={(e) => { setOffset(0); setStatus(e.target.value as DispatchStatus | 'UNHEALTHY' | ''); }}
            className="mt-1 rounded border border-gray-300 px-2 py-1.5 text-sm">
            <option value="UNHEALTHY">미완결 전체</option>
            <option value="">전체</option>
            {(Object.keys(DISPATCH_STATUS_LABEL) as DispatchStatus[]).map((s) => (
              <option key={s} value={s}>{DISPATCH_STATUS_LABEL[s]}</option>
            ))}
          </select>
        </label>
        <label className="text-sm text-gray-600">
          <span className="block text-xs text-gray-500">수신자 (정확일치)</span>
          <input value={recipient} onChange={(e) => setRecipient(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { setOffset(0); setApplied(recipient); } }}
            placeholder="ops@lemuel.co.kr"
            className="mt-1 rounded border border-gray-300 px-2 py-1.5 text-sm" />
        </label>
        <button type="button" onClick={() => { setOffset(0); setApplied(recipient); }}
          className="rounded border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700">
          조회
        </button>
        <span className="ml-auto text-sm text-gray-500">총 {total}건</span>
      </div>

      {error && (
        <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
      )}
      {notice && (
        <p role="status" className="rounded bg-sky-50 px-3 py-2 text-sm text-sky-800">{notice}</p>
      )}

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-xs uppercase text-gray-500">
            <tr>
              <th className="px-3 py-2 text-left font-semibold">상태</th>
              <th className="px-3 py-2 text-left font-semibold">종류</th>
              <th className="px-3 py-2 text-left font-semibold">수신자</th>
              <th className="px-3 py-2 text-left font-semibold">제목</th>
              <th className="px-3 py-2 text-right font-semibold">채널</th>
              <th className="px-3 py-2 text-left font-semibold">발송 시각</th>
              <th className="px-3 py-2 text-right font-semibold" />
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 ? (
              <tr>
                <td colSpan={7} className="py-10 text-center text-gray-400">
                  {loading ? '불러오는 중…' : '조건에 맞는 발송이 없습니다.'}
                </td>
              </tr>
            ) : rows.map((row) => (
              <tr key={row.id} className="hover:bg-blue-50/40">
                <td className="px-3 py-2">
                  <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-semibold ${DISPATCH_STATUS_BADGE[row.status]}`}>
                    {DISPATCH_STATUS_LABEL[row.status]}
                  </span>
                </td>
                <td className="px-3 py-2 text-gray-600">{row.type}</td>
                <td className="px-3 py-2 text-gray-800">{row.recipient}</td>
                <td className="px-3 py-2">
                  <button type="button" onClick={() => void openDetail(row.id)}
                    className="text-left text-blue-700 hover:underline">
                    {row.subject}
                  </button>
                  {row.resentFromId != null && (
                    <span className="ml-2 rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">
                      #{row.resentFromId} 재발송
                    </span>
                  )}
                </td>
                <td className="px-3 py-2 text-right text-gray-600">
                  {row.channelsSucceeded}/{row.channelsTotal}
                </td>
                <td className="px-3 py-2 text-gray-600">{fmt(row.createdAt)}</td>
                <td className="px-3 py-2 text-right">
                  <button type="button" onClick={() => void resend(row.id)} disabled={resending === row.id}
                    className="rounded border border-gray-300 px-2 py-1 text-xs font-semibold text-gray-700 disabled:opacity-50">
                    {resending === row.id ? '발송 중…' : '재발송'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {canPage && total > PAGE_SIZE && (
        <div className="flex items-center justify-end gap-2 text-sm">
          <button type="button" disabled={offset === 0 || loading}
            onClick={() => setOffset(Math.max(offset - PAGE_SIZE, 0))}
            className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">이전</button>
          <span className="text-gray-500">{offset + 1}–{Math.min(offset + PAGE_SIZE, total)}</span>
          <button type="button" disabled={offset + PAGE_SIZE >= total || loading}
            onClick={() => setOffset(offset + PAGE_SIZE)}
            className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">다음</button>
        </div>
      )}

      {detail && (
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-3 space-y-2">
          <div className="flex items-start justify-between gap-2">
            <div>
              <h3 className="font-semibold text-gray-900">{detail.subject}</h3>
              <p className="text-xs text-gray-500">
                eventId {detail.eventId} · 완료 {fmt(detail.completedAt)}
              </p>
            </div>
            <button type="button" onClick={() => setDetail(null)}
              className="text-sm text-gray-500 hover:text-gray-800">닫기</button>
          </div>
          {detail.body && (
            <pre className="whitespace-pre-wrap rounded bg-white p-2 text-sm text-gray-700">{detail.body}</pre>
          )}
          <ul className="space-y-1 text-sm">
            {detail.channels.length === 0 ? (
              <li className="text-gray-500">채널 결과가 없습니다 — 활성 채널이 0개였거나 아직 완료되지 않았습니다.</li>
            ) : detail.channels.map((c) => (
              <li key={c.channel} className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-xs text-gray-700">{c.channel}</span>
                <span className={c.status === 'SUCCESS' ? 'text-green-700' : 'text-red-700'}>
                  {c.status === 'SUCCESS' ? '성공' : '실패'}
                </span>
                <span className="text-xs text-gray-500">{c.attempts}회 시도</span>
                {c.error && <span className="text-xs text-red-600">{c.error}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
};

export default NotificationDispatchPanel;
