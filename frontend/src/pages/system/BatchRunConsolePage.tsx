import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  batchRunApi,
  type BatchRunStatus,
  type BatchRunView,
  type RerunnableBatchView,
  type RerunResult,
} from '@/api/batchRun';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 배치 실행 원장 콘솔.
 *
 * <p><b>이 화면의 핵심은 목록이 아니라 맨 위의 "배치별 최근 실행" 이다.</b> 운영자가 알아야 하는
 * 값은 "무엇이 돌았나" 가 아니라 <b>마지막 성공이 언제인가</b> 다 — 매일 도는 배치의 마지막
 * 성공이 사흘 전이면 그 사흘이 구멍이고, 지금까지는 그 사실을 알 방법이 아예 없었다.
 * ShedLock 의 {@code shedlock} 테이블은 락을 잡았다는 사실만 남기지 결과를 남기지 않아서,
 * "잡고 나서 죽은 실행" 과 "성공한 실행" 이 그 표에서는 같아 보인다.
 *
 * <p>그래서 상태를 셋으로 그린다. {@code RUNNING} 이 남아 있는 행은 성공도 실패도 아니라
 * <b>끝을 못 본</b> 실행이다(파드가 중간에 죽었거나 {@code lockAtMostFor} 를 넘겨 락이 풀린 경우).
 * 실패와 같은 색으로 칠하면 "실패해서 알림이 갔겠지" 로 오독되는데, 이쪽은 <b>아무도 모르게</b>
 * 사라진 실행이다.
 *
 * <p>재실행 기본값이 서버와 <b>반대</b>다. 서버는 {@code dryRun} 을 안 보내면 실제 실행이지만
 * (명시적 "재실행" 호출이 아무것도 안 하면 더 헷갈리므로), 화면은 미리보기를 켠 채로 시작한다 —
 * 버튼을 잘못 누르는 비용이 API 를 잘못 부르는 비용보다 크고, 여기서 되돌리는 방법이 없다.
 */

const fmtDateTime = (s: string | null) =>
  s ? new Date(s).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'medium' }) : '-';

const STATUS_LABEL: Record<BatchRunStatus, string> = {
  RUNNING: '실행 중',
  SUCCEEDED: '성공',
  FAILED: '실패',
};

/** RUNNING 을 실패와 같은 색으로 칠하지 않는다 — 성격이 다른 사고다(위 주석 참조). */
const STATUS_CLASS: Record<BatchRunStatus, string> = {
  RUNNING: 'bg-amber-100 text-amber-800',
  SUCCEEDED: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
};

/** 마지막 실행이 이보다 오래됐으면 눈에 띄게 표시한다. 매일 도는 배치가 다수라 하루가 기준선이다. */
const STALE_HOURS = 26;

function hoursSince(iso: string): number {
  return (Date.now() - new Date(iso).getTime()) / 3_600_000;
}

/** 오늘 날짜(YYYY-MM-DD). 재실행 폼의 초기값 — 널을 보내면 서버가 400 이다. */
function todayIso(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

const StatusBadge: React.FC<{ status: BatchRunStatus }> = ({ status }) => (
  <span className={`rounded px-2 py-0.5 text-xs font-semibold ${STATUS_CLASS[status]}`}>
    {STATUS_LABEL[status]}
  </span>
);

const BatchRunConsolePage: React.FC = () => {
  const [latest, setLatest] = useState<BatchRunView[] | null>(null);
  const [latestError, setLatestError] = useState<string | null>(null);

  const [rerunnable, setRerunnable] = useState<RerunnableBatchView[]>([]);

  const [batchName, setBatchName] = useState('');
  const [status, setStatus] = useState<BatchRunStatus | ''>('');
  const [targetDate, setTargetDate] = useState('');
  const [rows, setRows] = useState<BatchRunView[] | null>(null);
  const [totalElements, setTotalElements] = useState(0);
  const [listError, setListError] = useState<string | null>(null);

  const [rerunName, setRerunName] = useState('');
  const [rerunDate, setRerunDate] = useState(todayIso());
  const [dryRun, setDryRun] = useState(true);
  const [rerunning, setRerunning] = useState(false);
  const [rerunResult, setRerunResult] = useState<RerunResult | null>(null);
  const [rerunError, setRerunError] = useState<string | null>(null);

  const loadLatest = useCallback(async () => {
    setLatestError(null);
    try {
      setLatest(await batchRunApi.latest());
    } catch (err) {
      // 빈 표를 그리면 조회 실패가 "배치가 하나도 없음" 으로 위장한다 — 그 둘을 뭉개지 않는다.
      setLatest(null);
      setLatestError(apiErrorMessage(err, '배치별 최근 실행을 불러오지 못했습니다.'));
    }
  }, []);

  const loadList = useCallback(async () => {
    setListError(null);
    try {
      const page = await batchRunApi.search({
        batchName: batchName || undefined,
        status: status || undefined,
        targetDate: targetDate || undefined,
        size: 50,
      });
      setRows(page.content);
      setTotalElements(page.totalElements);
    } catch (err) {
      setRows(null);
      setListError(apiErrorMessage(err, '실행 이력을 불러오지 못했습니다.'));
    }
  }, [batchName, status, targetDate]);

  useEffect(() => { void loadLatest(); }, [loadLatest]);
  useEffect(() => { void loadList(); }, [loadList]);

  useEffect(() => {
    // 재실행 가능 목록이 비어도 화면은 성립한다(조회는 그대로 된다) — 그래서 오류를 띄우지 않는다.
    batchRunApi.rerunnable().then(setRerunnable).catch(() => setRerunnable([]));
  }, []);

  const selected = useMemo(
    () => rerunnable.find((b) => b.batchName === rerunName) ?? null,
    [rerunnable, rerunName],
  );

  const staleCount = (latest ?? []).filter(
    (r) => r.status !== 'SUCCEEDED' || hoursSince(r.startedAt) > STALE_HOURS,
  ).length;

  const submitRerun = async () => {
    if (!rerunName || !rerunDate) return;
    setRerunning(true);
    setRerunError(null);
    setRerunResult(null);
    try {
      const result = await batchRunApi.rerun(rerunName, rerunDate, dryRun);
      setRerunResult(result);
      // 실제 실행이면 원장이 늘었다. 미리보기는 아무것도 바꾸지 않으므로 다시 읽지 않는다.
      if (!result.dryRun) {
        await Promise.all([loadLatest(), loadList()]);
      }
    } catch (err) {
      setRerunError(apiErrorMessage(err, '재실행에 실패했습니다.'));
    } finally {
      setRerunning(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto space-y-6">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">배치 실행 원장</h1>
            <p className="text-sm text-gray-500 mt-1">
              스케줄러의 락 기록은 <b>락을 잡았다는 사실</b>만 남깁니다. 잡고 나서 죽은 실행과
              성공한 실행이 그 표에서는 같아 보여서, 결과를 따로 남기고 여기서 읽습니다.
            </p>
          </div>
          <button type="button" onClick={() => { void loadLatest(); void loadList(); }}
            className="shrink-0 rounded border border-gray-300 bg-white px-3 py-2 text-sm font-semibold text-gray-700">
            새로고침
          </button>
        </div>

        {/* ── 배치별 최근 실행 — 이 화면의 핵심 ── */}
        <section className="space-y-2">
          <h2 className="text-lg font-semibold text-gray-900">배치별 최근 실행</h2>
          {latestError && <p role="alert" className="text-sm text-red-600">{latestError}</p>}

          {latest !== null && (
            <p className={`rounded p-3 text-sm ${staleCount > 0 ? 'bg-red-50 text-red-800' : 'bg-green-50 text-green-800'}`}
              data-testid="stale-summary">
              {staleCount > 0
                ? <>마지막 실행이 성공이 아니거나 {STALE_HOURS}시간을 넘긴 배치가 <b>{staleCount}개</b> 있습니다.</>
                : <>모든 배치가 최근 {STALE_HOURS}시간 안에 성공했습니다.</>}
            </p>
          )}

          {latest === null ? (
            !latestError && <p className="text-sm text-gray-500">불러오는 중…</p>
          ) : latest.length === 0 ? (
            <p className="text-sm text-gray-600" data-testid="latest-empty">
              기록된 배치 실행이 없습니다. 원장이 도입되기 전 실행은 여기 남지 않습니다.
            </p>
          ) : (
            <table className="w-full text-sm" data-testid="latest-table">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">배치</th><th>상태</th><th>대상 날짜</th>
                  <th>시작</th><th>종료</th><th className="text-right">처리</th><th>실행 주체</th>
                </tr>
              </thead>
              <tbody>
                {latest.map((r) => {
                  const stale = r.status !== 'SUCCEEDED' || hoursSince(r.startedAt) > STALE_HOURS;
                  return (
                    <tr key={r.batchName} className={`border-t ${stale ? 'bg-red-50' : ''}`}
                      data-testid={`latest-row-${r.batchName}`}>
                      <td className="py-2 font-mono">{r.batchName}</td>
                      <td><StatusBadge status={r.status} /></td>
                      <td>{r.targetDate}</td>
                      <td className="text-xs text-gray-500">{fmtDateTime(r.startedAt)}</td>
                      <td className="text-xs text-gray-500">
                        {r.status === 'RUNNING'
                          // 끝나지 않은 실행에 '-' 를 찍으면 "종료 시각이 없는 성공" 처럼 읽힌다.
                          ? <span data-testid={`unfinished-${r.batchName}`}>끝을 못 봄</span>
                          : fmtDateTime(r.completedAt)}
                      </td>
                      <td className="text-right">{r.processedCount ?? '-'}</td>
                      <td className="text-xs text-gray-500">{r.triggeredBy}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </section>

        {/* ── 놓친 날짜분 재실행 ── */}
        <section className="space-y-2 rounded border border-gray-200 bg-white p-4">
          <h2 className="text-lg font-semibold text-gray-900">놓친 날짜분 재실행</h2>
          <p className="text-xs text-gray-500">
            여기 없는 배치는 날짜 지정 재실행 대상이 아닙니다. 미리보기는 대상 건수만 세고
            상태를 바꾸지 않습니다.
          </p>

          <div className="flex flex-wrap items-end gap-3">
            <label className="text-sm">
              <span className="block text-gray-600">배치</span>
              <select value={rerunName} onChange={(e) => { setRerunName(e.target.value); setRerunResult(null); }}
                aria-label="재실행할 배치"
                className="mt-1 rounded border border-gray-300 px-2 py-1.5">
                <option value="">선택하세요</option>
                {rerunnable.map((b) => (
                  <option key={b.batchName} value={b.batchName}>{b.batchName}</option>
                ))}
              </select>
            </label>

            <label className="text-sm">
              <span className="block text-gray-600">대상 날짜</span>
              <input type="date" value={rerunDate} onChange={(e) => setRerunDate(e.target.value)}
                aria-label="재실행 대상 날짜"
                className="mt-1 rounded border border-gray-300 px-2 py-1.5" />
            </label>

            <label className="flex items-center gap-2 text-sm text-gray-700">
              <input type="checkbox" checked={dryRun} onChange={(e) => setDryRun(e.target.checked)}
                disabled={selected !== null && !selected.supportsDryRun}
                aria-label="미리보기만 실행" />
              미리보기만 (상태를 바꾸지 않음)
            </label>

            <button type="button" onClick={() => void submitRerun()}
              disabled={rerunning || !rerunName || !rerunDate}
              className="rounded bg-gray-900 px-3 py-2 text-sm font-semibold text-white disabled:opacity-40">
              {rerunning ? '실행 중…' : dryRun ? '미리보기' : '실제 재실행'}
            </button>
          </div>

          {selected && (
            <p className="text-xs text-gray-600" data-testid="rerun-description">
              {selected.description}
              {!selected.supportsDryRun && (
                <b className="text-red-700"> — 이 배치는 미리보기를 지원하지 않습니다.</b>
              )}
            </p>
          )}

          {rerunError && <p role="alert" className="text-sm text-red-600">{rerunError}</p>}
          {rerunResult && (
            <p className={`rounded p-3 text-sm ${rerunResult.dryRun ? 'bg-gray-100 text-gray-800' : 'bg-green-50 text-green-800'}`}
              data-testid="rerun-result">
              {rerunResult.batchName} · {rerunResult.targetDate} ·{' '}
              {rerunResult.dryRun
                ? <>미리보기: 대상 <b>{rerunResult.processedCount}건</b> (아무것도 바뀌지 않았습니다)</>
                : <>재실행 완료: <b>{rerunResult.processedCount}건</b> 처리</>}
            </p>
          )}
        </section>

        {/* ── 실행 이력 ── */}
        <section className="space-y-2">
          <h2 className="text-lg font-semibold text-gray-900">실행 이력</h2>

          <div className="flex flex-wrap items-end gap-3">
            <label className="text-sm">
              <span className="block text-gray-600">배치명</span>
              <input value={batchName} onChange={(e) => setBatchName(e.target.value)}
                aria-label="배치명으로 거르기" placeholder="전체"
                className="mt-1 rounded border border-gray-300 px-2 py-1.5" />
            </label>
            <label className="text-sm">
              <span className="block text-gray-600">상태</span>
              <select value={status} onChange={(e) => setStatus(e.target.value as BatchRunStatus | '')}
                aria-label="상태로 거르기"
                className="mt-1 rounded border border-gray-300 px-2 py-1.5">
                <option value="">전체</option>
                <option value="RUNNING">{STATUS_LABEL.RUNNING}</option>
                <option value="SUCCEEDED">{STATUS_LABEL.SUCCEEDED}</option>
                <option value="FAILED">{STATUS_LABEL.FAILED}</option>
              </select>
            </label>
            <label className="text-sm">
              <span className="block text-gray-600">대상 날짜</span>
              <input type="date" value={targetDate} onChange={(e) => setTargetDate(e.target.value)}
                aria-label="대상 날짜로 거르기"
                className="mt-1 rounded border border-gray-300 px-2 py-1.5" />
            </label>
          </div>

          {listError && <p role="alert" className="text-sm text-red-600">{listError}</p>}

          {rows === null ? (
            !listError && <p className="text-sm text-gray-500">불러오는 중…</p>
          ) : rows.length === 0 ? (
            <p className="text-sm text-gray-600" data-testid="history-empty">
              조건에 맞는 실행 이력이 없습니다.
            </p>
          ) : (
            <>
              <p className="text-xs text-gray-500" data-testid="history-count">
                총 {totalElements}건 중 {rows.length}건
              </p>
              <table className="w-full text-sm" data-testid="history-table">
                <thead className="text-left text-gray-500">
                  <tr>
                    <th className="py-2">#</th><th>배치</th><th>상태</th><th>대상 날짜</th>
                    <th>시작</th><th className="text-right">처리</th><th>실행 주체</th><th>오류</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} className="border-t" data-testid={`history-row-${r.id}`}>
                      <td className="py-2">{r.id}</td>
                      <td className="font-mono">{r.batchName}</td>
                      <td><StatusBadge status={r.status} /></td>
                      <td>{r.targetDate}</td>
                      <td className="text-xs text-gray-500">{fmtDateTime(r.startedAt)}</td>
                      <td className="text-right">{r.processedCount ?? '-'}</td>
                      <td className="text-xs text-gray-500">{r.triggeredBy}</td>
                      <td className="max-w-xs truncate text-gray-700" title={r.errorMessage ?? ''}>
                        {r.errorMessage ?? '-'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </section>
      </div>
    </div>
  );
};

export default BatchRunConsolePage;
