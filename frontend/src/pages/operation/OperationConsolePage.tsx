import React, { useCallback, useEffect, useState } from 'react';
import Spinner from '@/components/Spinner';
import PgRoutingHealthCard from '@/components/PgRoutingHealthCard';
import { useToast } from '@/contexts/useToast';
import {
  operationApi,
  Incident,
  IncidentDetail,
  IncidentSearchParams,
  IncidentSummary,
  IncidentStatus,
  IncidentSeverity,
  SignalCategory,
  SummaryWindow,
  STATUS_LABEL,
  STATUS_BADGE,
  SEVERITY_LABEL,
  SEVERITY_BADGE,
  CATEGORY_LABEL,
} from '@/api/operation';
import { apiErrorStatus } from '@/lib/apiError';

const PAGE_SIZE = 20;

const STATUS_OPTIONS: IncidentStatus[] = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'FALSE_POSITIVE'];
const SEVERITY_OPTIONS: IncidentSeverity[] = ['CRITICAL', 'WARNING', 'INFO'];
const CATEGORY_OPTIONS = Object.keys(CATEGORY_LABEL) as SignalCategory[];
const WINDOW_OPTIONS: SummaryWindow[] = ['1h', '24h', '7d'];

const fmt = (iso: string | null): string => {
  if (!iso) return '-';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '-' : d.toLocaleString('ko-KR', { hour12: false });
};

const fmtMttr = (seconds: number | null): string => {
  if (seconds == null) return '-';
  if (seconds < 60) return `${seconds}초`;
  const m = Math.floor(seconds / 60);
  if (m < 60) return `${m}분`;
  const h = Math.floor(m / 60);
  return `${h}시간 ${m % 60}분`;
};

const OperationConsolePage: React.FC = () => {
  const { showToast } = useToast();

  // ── 요약 ──
  const [window, setWindow] = useState<SummaryWindow>('24h');
  const [summary, setSummary] = useState<IncidentSummary | null>(null);

  // ── 목록 ──
  const [filters, setFilters] = useState<Omit<IncidentSearchParams, 'page' | 'size'>>({});
  const [page, setPage] = useState(0);
  const [pageData, setPageData] = useState<{ content: Incident[]; totalElements: number; totalPages: number }>({
    content: [], totalElements: 0, totalPages: 0,
  });
  const [loading, setLoading] = useState(true);

  // ── 상세 ──
  const [detail, setDetail] = useState<IncidentDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadSummary = useCallback(async (w: SummaryWindow) => {
    try {
      setSummary(await operationApi.summary(w));
    } catch {
      showToast('요약 정보를 불러오지 못했습니다.', 'error');
    }
  }, [showToast]);

  const loadList = useCallback(async (p: number, f: typeof filters) => {
    setLoading(true);
    try {
      const res = await operationApi.search({ ...f, page: p, size: PAGE_SIZE });
      setPageData({ content: res.content, totalElements: res.totalElements, totalPages: res.totalPages });
    } catch {
      showToast('인시던트 목록을 불러오지 못했습니다.', 'error');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => { loadSummary(window); }, [window, loadSummary]);
  useEffect(() => { loadList(page, filters); }, [page, filters, loadList]);

  const refresh = () => { loadSummary(window); loadList(page, filters); };

  const openDetail = async (id: number) => {
    setDetailLoading(true);
    setDetail(null);
    try {
      setDetail(await operationApi.get(id));
    } catch {
      showToast('상세 정보를 불러오지 못했습니다.', 'error');
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  };

  const setFilter = (key: keyof typeof filters, value: string) => {
    setPage(0);
    setFilters((prev) => {
      const next = { ...prev };
      if (value) (next as Record<string, string>)[key] = value;
      else delete next[key];
      return next;
    });
  };

  const runAction = async (
    action: (id: number, note?: string) => Promise<IncidentDetail>,
    id: number,
    successMsg: string,
    withNote = false,
  ) => {
    let note: string | undefined;
    if (withNote) {
      const input = prompt('메모 (선택):') ?? '';
      note = input.trim() || undefined;
    }
    try {
      const updated = await action(id, note);
      setDetail(updated);
      showToast(successMsg, 'success');
      refresh();
    } catch (err) {
      const status = apiErrorStatus(err);
      showToast(status === 409 ? '현재 상태에서 처리할 수 없습니다 (상태 충돌).' : '처리에 실패했습니다.', 'error');
    }
  };

  const addComment = async (id: number) => {
    const input = prompt('코멘트를 입력하세요:');
    if (input == null) return;
    const note = input.trim();
    if (!note) return;
    try {
      setDetail(await operationApi.comment(id, note));
      showToast('코멘트를 추가했습니다.', 'success');
    } catch {
      showToast('코멘트 추가에 실패했습니다.', 'error');
    }
  };

  const byStatus = summary?.byStatus ?? {};

  return (
    // 시스템 관리 그룹(SideNavLayout) 콘텐츠 영역에서 렌더링 — 컨테이너/패딩은 레이아웃이 제공
    <div>
      {/* 헤더 */}
      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
            <span>🖥️</span> 운영 관제
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            operation-service · Alertmanager 인시던트 라이프사이클 조회·관리
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="inline-flex rounded-lg border border-gray-200 bg-white p-0.5">
            {WINDOW_OPTIONS.map((w) => (
              <button
                key={w}
                onClick={() => setWindow(w)}
                className={`px-3 py-1.5 text-sm font-semibold rounded-md transition-colors ${
                  window === w ? 'bg-gray-900 text-white' : 'text-gray-600 hover:bg-gray-100'
                }`}
              >
                {w}
              </button>
            ))}
          </div>
          <button
            onClick={refresh}
            className="px-4 py-1.5 text-sm font-semibold rounded-lg bg-blue-600 text-white hover:bg-blue-700"
          >
            새로고침
          </button>
        </div>
      </div>

      {/* PG 라우터 상태 — 인시던트를 보러 온 자리에서 "결제가 어느 경로로 나가는지"를 함께 본다.
          order-service 표면이지만 읽는 맥락이 관제라 여기 둔다(장애 중 즉시 확인). */}
      <PgRoutingHealthCard />

      {/* 요약 카드 */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-4">
        <SummaryCard label="활성 인시던트" value={summary?.openTotal ?? '-'} accent="text-red-600" sub={`${window} 기준`} />
        <SummaryCard label="열림 / 확인됨"
          value={`${byStatus.OPEN ?? 0} / ${byStatus.ACKNOWLEDGED ?? 0}`} accent="text-amber-600" sub="OPEN / ACKNOWLEDGED" />
        <SummaryCard label="해결됨" value={byStatus.RESOLVED ?? 0} accent="text-green-600" sub={`오탐 ${byStatus.FALSE_POSITIVE ?? 0}`} />
        <SummaryCard label="평균 해결 시간(MTTR)" value={fmtMttr(summary?.mttrSeconds ?? null)} accent="text-gray-900" sub={`${window} RESOLVED 평균`} />
      </div>

      {/* 심각도/카테고리 분포 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-6">
        <DistributionCard title="심각도별"
          entries={Object.entries(summary?.bySeverity ?? {})}
          labelOf={(k) => SEVERITY_LABEL[k as IncidentSeverity] ?? k} />
        <DistributionCard title="카테고리별"
          entries={Object.entries(summary?.byCategory ?? {})}
          labelOf={(k) => CATEGORY_LABEL[k as SignalCategory] ?? k} />
      </div>

      {/* 필터 */}
      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4 flex flex-wrap items-end gap-3">
        <FilterSelect label="상태" value={filters.status ?? ''}
          onChange={(v) => setFilter('status', v)}
          options={STATUS_OPTIONS.map((s) => ({ value: s, label: STATUS_LABEL[s] }))} />
        <FilterSelect label="심각도" value={filters.severity ?? ''}
          onChange={(v) => setFilter('severity', v)}
          options={SEVERITY_OPTIONS.map((s) => ({ value: s, label: SEVERITY_LABEL[s] }))} />
        <FilterSelect label="카테고리" value={filters.category ?? ''}
          onChange={(v) => setFilter('category', v)}
          options={CATEGORY_OPTIONS.map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))} />
        {(filters.status || filters.severity || filters.category) && (
          <button
            onClick={() => { setPage(0); setFilters({}); }}
            className="px-3 py-2 text-sm text-gray-500 hover:text-gray-800"
          >
            필터 초기화
          </button>
        )}
        <span className="ml-auto text-sm text-gray-500 self-center">총 {pageData.totalElements}건</span>
      </div>

      {/* 목록 테이블 */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-4 py-3 text-left font-semibold">상태</th>
                <th className="px-4 py-3 text-left font-semibold">심각도</th>
                <th className="px-4 py-3 text-left font-semibold">제목</th>
                <th className="px-4 py-3 text-left font-semibold">카테고리</th>
                <th className="px-4 py-3 text-left font-semibold">서비스</th>
                <th className="px-4 py-3 text-right font-semibold">발생</th>
                <th className="px-4 py-3 text-left font-semibold">최근 감지</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {loading ? (
                <tr><td colSpan={7} className="py-16"><Spinner message="불러오는 중..." /></td></tr>
              ) : pageData.content.length === 0 ? (
                <tr><td colSpan={7} className="py-16 text-center text-gray-400">조건에 맞는 인시던트가 없습니다.</td></tr>
              ) : (
                pageData.content.map((inc) => (
                  <tr key={inc.id} onClick={() => openDetail(inc.id)}
                      className="cursor-pointer hover:bg-blue-50/50 transition-colors">
                    <td className="px-4 py-3">
                      <span className={`inline-block px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_BADGE[inc.status]}`}>
                        {STATUS_LABEL[inc.status]}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`inline-block px-2 py-0.5 rounded text-xs font-bold ${SEVERITY_BADGE[inc.severity]}`}>
                        {SEVERITY_LABEL[inc.severity]}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-medium text-gray-900 max-w-xs truncate">{inc.title}</td>
                    <td className="px-4 py-3 text-gray-600">{CATEGORY_LABEL[inc.category] ?? inc.category}</td>
                    <td className="px-4 py-3 text-gray-500 font-mono text-xs">{inc.service ?? '-'}</td>
                    <td className="px-4 py-3 text-right text-gray-600">{inc.occurrenceCount}</td>
                    <td className="px-4 py-3 text-gray-500 whitespace-nowrap">{fmt(inc.lastSeenAt)}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* 페이지네이션 */}
        {pageData.totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-gray-100">
            <button
              disabled={page <= 0}
              onClick={() => setPage((p) => Math.max(p - 1, 0))}
              className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 disabled:opacity-40 hover:bg-gray-50"
            >
              이전
            </button>
            <span className="text-sm text-gray-500">{page + 1} / {pageData.totalPages}</span>
            <button
              disabled={page >= pageData.totalPages - 1}
              onClick={() => setPage((p) => Math.min(p + 1, pageData.totalPages - 1))}
              className="px-3 py-1.5 text-sm rounded-lg border border-gray-200 disabled:opacity-40 hover:bg-gray-50"
            >
              다음
            </button>
          </div>
        )}
      </div>

      {/* 상세 드로어 */}
      {(detail || detailLoading) && (
        <IncidentDetailDrawer
          detail={detail}
          loading={detailLoading}
          onClose={() => setDetail(null)}
          onAck={(id) => runAction(operationApi.acknowledge, id, '확인 처리했습니다.', true)}
          onResolve={(id) => runAction(operationApi.resolve, id, '해결 처리했습니다.', true)}
          onFalsePositive={(id) => runAction(operationApi.markFalsePositive, id, '오탐 처리했습니다.', true)}
          onComment={addComment}
        />
      )}
    </div>
  );
};

// ── 하위 컴포넌트 ────────────────────────────────────────────────────────────

const SummaryCard: React.FC<{ label: string; value: React.ReactNode; accent: string; sub?: string }> = ({ label, value, accent, sub }) => (
  <div className="bg-white rounded-xl border border-gray-200 p-4">
    <p className="text-xs font-semibold text-gray-500">{label}</p>
    <p className={`text-2xl font-bold mt-1 ${accent}`}>{value}</p>
    {sub && <p className="text-xs text-gray-400 mt-0.5">{sub}</p>}
  </div>
);

const DistributionCard: React.FC<{ title: string; entries: [string, number][]; labelOf: (k: string) => string }> = ({ title, entries, labelOf }) => (
  <div className="bg-white rounded-xl border border-gray-200 p-4">
    <p className="text-sm font-bold text-gray-700 mb-3">{title}</p>
    {entries.length === 0 ? (
      <p className="text-sm text-gray-400">데이터 없음</p>
    ) : (
      <div className="flex flex-wrap gap-2">
        {entries.map(([k, v]) => (
          <span key={k} className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-gray-50 border border-gray-100 text-sm">
            <span className="text-gray-600">{labelOf(k)}</span>
            <span className="font-bold text-gray-900">{v}</span>
          </span>
        ))}
      </div>
    )}
  </div>
);

const FilterSelect: React.FC<{
  label: string; value: string; onChange: (v: string) => void; options: { value: string; label: string }[];
}> = ({ label, value, onChange, options }) => (
  <label className="flex flex-col gap-1">
    <span className="text-xs font-semibold text-gray-500">{label}</span>
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="px-3 py-2 text-sm rounded-lg border border-gray-200 bg-white min-w-[130px] focus:outline-none focus:ring-2 focus:ring-blue-500"
    >
      <option value="">전체</option>
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  </label>
);

const TIMELINE_LABEL: Record<string, string> = {
  OPENED: '생성', REFIRED: '재발생', ACKNOWLEDGED: '확인',
  RESOLVED: '해결', AUTO_RESOLVED: '자동 해결', FALSE_POSITIVE: '오탐', COMMENT: '코멘트',
};

const IncidentDetailDrawer: React.FC<{
  detail: IncidentDetail | null;
  loading: boolean;
  onClose: () => void;
  onAck: (id: number) => void;
  onResolve: (id: number) => void;
  onFalsePositive: (id: number) => void;
  onComment: (id: number) => void;
}> = ({ detail, loading, onClose, onAck, onResolve, onFalsePositive, onComment }) => {
  const inc = detail?.incident;
  const active = inc?.status === 'OPEN' || inc?.status === 'ACKNOWLEDGED';
  const runbookUrl = detail?.annotations?.runbook_url;

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="relative w-full max-w-xl bg-white h-full shadow-2xl overflow-y-auto">
        {loading || !detail || !inc ? (
          <div className="h-full flex items-center justify-center"><Spinner message="상세 로드 중..." /></div>
        ) : (
          <>
            <div className="sticky top-0 bg-white border-b border-gray-100 px-6 py-4 flex items-start justify-between gap-4">
              <div className="min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_BADGE[inc.status]}`}>{STATUS_LABEL[inc.status]}</span>
                  <span className={`px-2 py-0.5 rounded text-xs font-bold ${SEVERITY_BADGE[inc.severity]}`}>{SEVERITY_LABEL[inc.severity]}</span>
                </div>
                <h2 className="text-lg font-bold text-gray-900 break-words">{inc.title}</h2>
                <p className="text-xs text-gray-400 mt-0.5">#{inc.id} · {inc.source} · {CATEGORY_LABEL[inc.category] ?? inc.category}</p>
              </div>
              <button onClick={onClose} className="text-gray-400 hover:text-gray-700 text-xl leading-none">✕</button>
            </div>

            <div className="px-6 py-5 space-y-5">
              {/* 액션 */}
              <div className="flex flex-wrap gap-2">
                <button disabled={inc.status !== 'OPEN'} onClick={() => onAck(inc.id)}
                  className="px-3 py-1.5 text-sm font-semibold rounded-lg bg-amber-500 text-white hover:bg-amber-600 disabled:opacity-40">확인(ACK)</button>
                <button disabled={!active} onClick={() => onResolve(inc.id)}
                  className="px-3 py-1.5 text-sm font-semibold rounded-lg bg-green-600 text-white hover:bg-green-700 disabled:opacity-40">해결</button>
                <button disabled={!active} onClick={() => onFalsePositive(inc.id)}
                  className="px-3 py-1.5 text-sm font-semibold rounded-lg bg-gray-500 text-white hover:bg-gray-600 disabled:opacity-40">오탐</button>
                <button onClick={() => onComment(inc.id)}
                  className="px-3 py-1.5 text-sm font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-50">코멘트</button>
              </div>

              {/* 메타 */}
              <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
                <Meta label="서비스" value={inc.service ?? '-'} mono />
                <Meta label="발생 횟수" value={String(inc.occurrenceCount)} />
                <Meta label="최초 발생" value={fmt(inc.firstSeenAt)} />
                <Meta label="최근 감지" value={fmt(inc.lastSeenAt)} />
                <Meta label="확인" value={inc.acknowledgedBy ? `${inc.acknowledgedBy} · ${fmt(inc.acknowledgedAt)}` : '-'} />
                <Meta label="해결" value={inc.resolvedBy ? `${inc.resolvedBy} · ${fmt(inc.resolvedAt)}` : '-'} />
                <Meta label="상관 키" value={inc.correlationKey} mono full />
              </dl>

              {detail.description && (
                <div>
                  <p className="text-xs font-semibold text-gray-500 mb-1">설명</p>
                  <p className="text-sm text-gray-700 whitespace-pre-wrap bg-gray-50 rounded-lg p-3">{detail.description}</p>
                </div>
              )}

              {runbookUrl && (
                <a href={runbookUrl} target="_blank" rel="noreferrer"
                  className="inline-flex items-center gap-1.5 text-sm font-semibold text-blue-600 hover:text-blue-800">
                  📖 런북 열기 →
                </a>
              )}

              {/* 타임라인 */}
              <div>
                <p className="text-xs font-semibold text-gray-500 mb-2">타임라인</p>
                <ol className="relative border-l-2 border-gray-100 ml-1 space-y-4">
                  {detail.timeline.map((t, idx) => (
                    <li key={idx} className="ml-4">
                      <span className="absolute -left-[7px] w-3 h-3 rounded-full bg-gray-300 border-2 border-white" />
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-semibold text-gray-800">{TIMELINE_LABEL[t.eventType] ?? t.eventType}</span>
                        <span className="text-xs text-gray-400">{t.actor}</span>
                      </div>
                      <p className="text-xs text-gray-400">{fmt(t.createdAt)}</p>
                      {t.note && <p className="text-sm text-gray-600 mt-0.5">{t.note}</p>}
                    </li>
                  ))}
                  {detail.timeline.length === 0 && <li className="ml-4 text-sm text-gray-400">타임라인 없음</li>}
                </ol>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

const Meta: React.FC<{ label: string; value: string; mono?: boolean; full?: boolean }> = ({ label, value, mono, full }) => (
  <div className={full ? 'col-span-2' : ''}>
    <dt className="text-xs font-semibold text-gray-400">{label}</dt>
    <dd className={`text-gray-800 break-words ${mono ? 'font-mono text-xs' : ''}`}>{value}</dd>
  </div>
);

export default OperationConsolePage;
