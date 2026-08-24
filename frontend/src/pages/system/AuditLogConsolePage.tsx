import { useCallback, useEffect, useState } from 'react';
import {
  auditLogApi,
  saveBlob,
  type AuditActionCount,
  type AuditLogPage,
  type AuditLogQuery,
  type AuditScope,
} from '@/api/auditLog';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 감사 로그 콘솔.
 *
 * <p><b>왜 이 화면이 필요한가</b>: 정산 확정·지급 실행·권한 변경·로그인 실패는 오래전부터
 * `audit_logs` 에 적재돼 왔지만 읽는 경로가 없었다. 사고가 났을 때 DB 에 직접 붙는 것 외에
 * 방법이 없었고, 그 접근 자체가 또 감사 대상이 된다. 남기기만 하고 볼 수 없는 기록은
 * 증적이 아니다.
 *
 * <p><b>탭이 둘인 이유</b>: 감사 테이블은 서비스마다 자기 DB 에 따로 있다(MSA 경계). 커머스
 * 조작과 자금 조작은 애초에 다른 테이블이며, 하나로 합쳐 보여 주려면 서비스 간 조인이 필요해
 * 경계를 깨야 한다. 그래서 화면이 "어느 쪽을 보는가"를 명시적으로 고르게 한다.
 *
 * <p><b>기간을 화면이 늘 채워 보내는 이유</b>: 서버는 기간이 비면 최근 30일로 채운다. 화면이
 * 비워 보내면 사용자가 보는 기간 표시와 실제 조회 기간이 어긋나 "이 결과가 어느 기간인지"
 * 아무도 확신할 수 없게 된다. 표는 언제나 자기가 무엇을 보여 주는지 말할 수 있어야 한다.
 */

const PAGE_SIZE = 50;

const SCOPES: { key: AuditScope; label: string; hint: string }[] = [
  { key: 'COMMERCE', label: '커머스', hint: '로그인 · 권한 변경 · 환불 요청' },
  { key: 'SETTLEMENT', label: '정산', hint: '지급 실행 · 차지백 판정 · 대사 마감' },
];

const isoDaysAgo = (days: number) => {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
};

const today = () => new Date().toISOString().slice(0, 10);

export default function AuditLogConsolePage() {
  const [scope, setScope] = useState<AuditScope>('COMMERCE');

  const [from, setFrom] = useState(isoDaysAgo(30));
  const [to, setTo] = useState(today());
  const [actorEmail, setActorEmail] = useState('');
  const [action, setAction] = useState('');
  const [resourceType, setResourceType] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [page, setPage] = useState(0);

  const [actions, setActions] = useState<string[]>([]);
  const [result, setResult] = useState<AuditLogPage | null>(null);
  const [counts, setCounts] = useState<AuditActionCount[]>([]);
  const [expanded, setExpanded] = useState<number | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const query = useCallback(
    (withPaging: boolean): AuditLogQuery => ({
      from,
      to,
      actorEmail: actorEmail.trim() || undefined,
      action: action || undefined,
      resourceType: resourceType.trim() || undefined,
      resourceId: resourceId.trim() || undefined,
      ...(withPaging ? { page, size: PAGE_SIZE } : {}),
    }),
    [from, to, actorEmail, action, resourceType, resourceId, page],
  );

  /** 필터 드롭다운은 서버 enum 이 정본이다 — 화면에 하드코딩하면 새 액션이 조용히 빠진다. */
  useEffect(() => {
    let cancelled = false;
    void auditLogApi
      .actions(scope)
      .then(list => {
        if (!cancelled) setActions(list);
      })
      .catch(() => {
        if (!cancelled) setActions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [scope]);

  const load = useCallback(async () => {
    setError(null);
    setLoading(true);
    try {
      const [pageResult, actionCounts] = await Promise.all([
        auditLogApi.search(scope, query(true)),
        auditLogApi.actionCounts(scope, query(false)),
      ]);
      setResult(pageResult);
      setCounts(actionCounts);
      setExpanded(null);
    } catch (err) {
      setError(apiErrorMessage(err, '감사 로그를 불러오지 못했습니다.'));
      setResult(null);
      setCounts([]);
    } finally {
      setLoading(false);
    }
  }, [scope, query]);

  useEffect(() => {
    void load();
  }, [load]);

  const search = () => {
    if (page === 0) {
      void load();
    } else {
      setPage(0); // page 변경이 load 를 다시 트리거한다.
    }
  };

  const download = async () => {
    setNotice(null);
    setError(null);
    try {
      const exported = await auditLogApi.export(scope, query(false));
      saveBlob(exported.blob, exported.fileName);
      setNotice(
        exported.truncated
          ? `내려받은 CSV 는 조건에 맞는 ${exported.total.toLocaleString()}건 중 앞 5,000건만 담고 있습니다. 기간을 좁혀 다시 받으세요.`
          : `${exported.total.toLocaleString()}건을 모두 내려받았습니다.`,
      );
    } catch (err) {
      setError(apiErrorMessage(err, 'CSV 내려받기에 실패했습니다.'));
    }
  };

  const totalPages = result?.totalPages ?? 0;

  return (
    <main className="space-y-6 p-6">
      <header>
        <h1 className="text-2xl font-bold">감사 로그</h1>
        <p className="text-sm text-gray-500">
          누가 · 언제 · 무엇을 조작했는지 조회합니다. 조회 권한은 ADMIN 으로 제한됩니다.
        </p>
      </header>

      <div role="tablist" aria-label="감사 대상" className="flex gap-2">
        {SCOPES.map(item => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={scope === item.key}
            onClick={() => {
              setScope(item.key);
              setAction('');
              setPage(0);
            }}
            className={`rounded px-4 py-2 text-sm ${
              scope === item.key ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700'
            }`}
          >
            {item.label}
            <span className="ml-2 text-xs opacity-75">{item.hint}</span>
          </button>
        ))}
      </div>

      <section className="grid gap-3 rounded border p-4 sm:grid-cols-3">
        <label className="flex flex-col gap-1">
          <span className="text-sm">시작일</span>
          <input aria-label="시작일" type="date" value={from} onChange={e => setFrom(e.target.value)}
            className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">종료일</span>
          <input aria-label="종료일" type="date" value={to} onChange={e => setTo(e.target.value)}
            className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">액션</span>
          <select aria-label="액션" value={action} onChange={e => setAction(e.target.value)}
            className="rounded border px-3 py-2">
            <option value="">전체</option>
            {actions.map(name => (
              <option key={name} value={name}>{name}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">행위자 이메일</span>
          <input aria-label="행위자 이메일" value={actorEmail} onChange={e => setActorEmail(e.target.value)}
            placeholder="일부만 입력해도 됩니다" className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">리소스 유형</span>
          <input aria-label="리소스 유형" value={resourceType} onChange={e => setResourceType(e.target.value)}
            placeholder="PAYOUT" className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">리소스 ID</span>
          <input aria-label="리소스 ID" value={resourceId} onChange={e => setResourceId(e.target.value)}
            className="rounded border px-3 py-2" />
        </label>

        <div className="flex items-end gap-2 sm:col-span-3">
          <button type="button" onClick={search} disabled={loading}
            className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
            {loading ? '조회 중…' : '조회'}
          </button>
          <button type="button" onClick={() => void download()} disabled={loading}
            className="rounded bg-slate-700 px-4 py-2 text-white disabled:opacity-50">
            CSV 내려받기
          </button>
        </div>
      </section>

      {error && <p role="alert" className="text-red-600">{error}</p>}
      {notice && <p role="status" className="rounded bg-amber-50 p-3 text-amber-800">{notice}</p>}

      {counts.length > 0 && (
        <section aria-label="액션별 건수" className="flex flex-wrap gap-2">
          {counts.map(item => (
            <button
              key={item.action}
              type="button"
              onClick={() => {
                setAction(item.action);
                setPage(0);
              }}
              className="rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-700 hover:bg-gray-200"
            >
              {item.action} <b>{item.count.toLocaleString()}</b>
            </button>
          ))}
        </section>
      )}

      <section>
        <p className="mb-2 text-sm text-gray-600">
          {from} ~ {to} · 총 {(result?.totalElements ?? 0).toLocaleString()}건
        </p>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-3 py-2">일시</th>
                <th className="px-3 py-2">행위자</th>
                <th className="px-3 py-2">액션</th>
                <th className="px-3 py-2">리소스</th>
                <th className="px-3 py-2">IP</th>
                <th className="px-3 py-2">상세</th>
              </tr>
            </thead>
            <tbody>
              {(result?.content ?? []).map(row => (
                <tr key={row.id} className="border-t align-top">
                  <td className="whitespace-nowrap px-3 py-2">{row.createdAt.replace('T', ' ')}</td>
                  <td className="px-3 py-2">{row.actorEmail ?? `#${row.actorId ?? '시스템'}`}</td>
                  <td className="px-3 py-2 font-medium">{row.action}</td>
                  <td className="px-3 py-2">
                    {row.resourceType ? `${row.resourceType}${row.resourceId ? ` / ${row.resourceId}` : ''}` : '—'}
                  </td>
                  <td className="px-3 py-2">{row.ipAddress ?? '—'}</td>
                  <td className="px-3 py-2">
                    {row.detailJson ? (
                      <button type="button" onClick={() => setExpanded(expanded === row.id ? null : row.id)}
                        className="text-blue-600 underline">
                        {expanded === row.id ? '접기' : '보기'}
                      </button>
                    ) : (
                      '—'
                    )}
                    {expanded === row.id && row.detailJson && (
                      <pre className="mt-2 max-w-lg overflow-x-auto rounded bg-gray-50 p-2 text-xs">
                        {row.detailJson}
                      </pre>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {!loading && (result?.content.length ?? 0) === 0 && (
          <p className="py-6 text-center text-gray-500">이 기간에 기록된 조작이 없습니다.</p>
        )}
      </section>

      {totalPages > 1 && (
        <nav aria-label="페이지" className="flex items-center gap-3">
          <button type="button" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0 || loading}
            className="rounded border px-3 py-1 disabled:opacity-40">
            이전
          </button>
          <span className="text-sm">{page + 1} / {totalPages}</span>
          <button type="button" onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1 || loading}
            className="rounded border px-3 py-1 disabled:opacity-40">
            다음
          </button>
        </nav>
      )}
    </main>
  );
}
