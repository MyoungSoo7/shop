import React, { useEffect, useState } from 'react';
import {
  bulkOrderApi,
  BulkOrderColumn,
  BulkOrderDraft,
  ConfirmResult,
} from '@/api/bulkOrder';
import { apiErrorMessage } from '@/lib/apiError';
import Spinner from '@/components/Spinner';

const STATUS_STYLE: Record<string, { label: string; cls: string }> = {
  UPLOADED: { label: '검증 대기', cls: 'bg-gray-100 text-gray-700' },
  VALIDATED: { label: '검증 통과', cls: 'bg-emerald-100 text-emerald-700' },
  REJECTED: { label: '오류 있음', cls: 'bg-red-100 text-red-700' },
  CONFIRMED: { label: '주문 전환 완료', cls: 'bg-blue-100 text-blue-700' },
  DISCARDED: { label: '폐기', cls: 'bg-gray-100 text-gray-400' },
};

/**
 * 대량주문 — CSV 업로드 → 검증 → 실주문 전환.
 *
 * 화면이 두 단계로 갈라져 있는 것이 이 기능의 요점이다: 올리는 것과 주문이 나가는 것이 같은
 * 버튼이면, 뒷쪽 한 행의 오타 때문에 앞쪽 수백 건을 취소·환불로 되돌려야 한다.
 *
 * 오류는 셀 단위로 표시한다 — "이 행 어딘가가 틀렸다"만 알려 주면 결국 눈으로 훑게 된다.
 */
const BulkOrderPage: React.FC = () => {
  const [columns, setColumns] = useState<BulkOrderColumn[]>([]);
  const [drafts, setDrafts] = useState<BulkOrderDraft[]>([]);
  const [selected, setSelected] = useState<BulkOrderDraft | null>(null);
  const [confirmResult, setConfirmResult] = useState<ConfirmResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = async () => {
    setDrafts(await bulkOrderApi.list());
  };

  useEffect(() => {
    (async () => {
      try {
        const [cols] = await Promise.all([bulkOrderApi.columns(), reload()]);
        setColumns(cols);
      } catch (err) {
        setError(apiErrorMessage(err, '대량주문 정보를 불러오지 못했습니다.'));
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (err) {
      setError(apiErrorMessage(err, '요청을 처리하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const handleUpload = (file: File) => run(async () => {
    setConfirmResult(null);
    const draft = await bulkOrderApi.upload(file);
    setSelected(draft);
    await reload();
  });

  const open = (id: number) => run(async () => {
    setConfirmResult(null);
    setSelected(await bulkOrderApi.get(id));
  });

  const revalidate = (id: number) => run(async () => {
    setSelected(await bulkOrderApi.revalidate(id));
    await reload();
  });

  const confirm = (id: number) => run(async () => {
    setConfirmResult(await bulkOrderApi.confirm(id));
    setSelected(await bulkOrderApi.get(id));
    await reload();
  });

  const discard = (id: number) => run(async () => {
    await bulkOrderApi.discard(id);
    setSelected(null);
    await reload();
  });

  /** 다운로드용 헤더 — 양식이 DB 에서 오므로 화면에 하드코딩하지 않는다. */
  const templateHeader = columns.map((c) => c.name).join(',');

  if (loading) return <Spinner size="md" message="대량주문 불러오는 중..." />;

  return (
    <div className="max-w-6xl mx-auto px-4 py-8 space-y-6">
      <header>
        <h1 className="text-2xl font-bold text-gray-900">대량주문</h1>
        <p className="mt-1 text-sm text-gray-500">
          CSV 를 올리면 <b>검증만</b> 합니다. 주문은 오류를 모두 고친 뒤 &lsquo;실주문 전환&rsquo;을 눌러야 나갑니다.
        </p>
      </header>

      {/* 양식 안내 — 열 정의가 서버(DB)에 있어 배포 없이 바뀐다 */}
      <section className="bg-white rounded-xl border border-gray-200 p-5">
        <h2 className="text-sm font-semibold text-gray-900 mb-3">업로드 양식</h2>
        <div className="overflow-x-auto">
          <table className="min-w-full text-xs">
            <thead>
              <tr className="text-left text-gray-500 border-b border-gray-200">
                <th className="py-2 pr-4">열</th>
                <th className="py-2 pr-4">항목</th>
                <th className="py-2 pr-4">필수</th>
                <th className="py-2 pr-4">최대 길이</th>
                <th className="py-2">형식</th>
              </tr>
            </thead>
            <tbody>
              {columns.map((c) => (
                <tr key={c.columnIndex} className="border-b border-gray-100">
                  <td className="py-1.5 pr-4 text-gray-400">{c.columnIndex + 1}</td>
                  <td className="py-1.5 pr-4 font-medium text-gray-800">{c.name}</td>
                  <td className="py-1.5 pr-4">{c.required ? '필수' : '선택'}</td>
                  <td className="py-1.5 pr-4">{c.maxLength ?? '-'}</td>
                  <td className="py-1.5 text-gray-500">
                    {c.validationType === 'NONE' ? '-' : c.validationType}
                    {c.validationText ? ` (${c.validationText})` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-3 text-xs text-gray-400">
          첫 줄은 헤더로 건너뜁니다. 예: <code className="text-gray-600">{templateHeader}</code>
        </p>

        <label className="mt-4 inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold cursor-pointer hover:bg-blue-700">
          CSV 업로드
          <input
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            disabled={busy}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleUpload(file);
              e.target.value = '';
            }}
          />
        </label>
      </section>

      {error && (
        <div className="rounded-lg bg-red-50 border border-red-200 p-3 text-sm text-red-800">{error}</div>
      )}

      {/* 초안 목록 */}
      <section className="bg-white rounded-xl border border-gray-200 p-5">
        <h2 className="text-sm font-semibold text-gray-900 mb-3">내 업로드 이력</h2>
        {drafts.length === 0 ? (
          <p className="text-sm text-gray-400 py-6 text-center">업로드한 파일이 없습니다.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {drafts.map((d) => {
              const badge = STATUS_STYLE[d.status] ?? { label: d.status, cls: 'bg-gray-100 text-gray-700' };
              return (
                <li key={d.id} className="py-2.5 flex items-center justify-between gap-3">
                  <button
                    type="button"
                    onClick={() => open(d.id)}
                    className="text-sm text-gray-800 hover:text-blue-700 text-left flex-1 truncate"
                  >
                    {d.fileName ?? `초안 #${d.id}`}
                  </button>
                  <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${badge.cls}`}>
                    {badge.label}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {/* 선택한 초안 상세 — 셀 단위 오류 */}
      {selected && (
        <section className="bg-white rounded-xl border border-gray-200 p-5 space-y-4">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="text-sm font-semibold text-gray-900">
                {selected.fileName ?? `초안 #${selected.id}`}
              </h2>
              <p className="text-xs text-gray-500 mt-0.5">
                {selected.rowCount}행 중 {selected.validRowCount}행 통과
              </p>
            </div>
            <span className={`text-xs font-semibold px-2 py-1 rounded-full ${
              (STATUS_STYLE[selected.status] ?? { cls: 'bg-gray-100 text-gray-700' }).cls}`}>
              {(STATUS_STYLE[selected.status] ?? { label: selected.status }).label}
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="min-w-full text-xs">
              <thead>
                <tr className="text-left text-gray-500 border-b border-gray-200">
                  <th className="py-2 pr-3">#</th>
                  {columns.map((c) => <th key={c.columnIndex} className="py-2 pr-3">{c.name}</th>)}
                  <th className="py-2">결과</th>
                </tr>
              </thead>
              <tbody>
                {selected.rows.map((row) => (
                  <tr key={row.rowNumber} className="border-b border-gray-100 align-top">
                    <td className="py-1.5 pr-3 text-gray-400">{row.rowNumber}</td>
                    {columns.map((c) => {
                      const cell = row.cells.find((x) => x.columnIndex === c.columnIndex);
                      return (
                        <td
                          key={c.columnIndex}
                          className={`py-1.5 pr-3 ${cell && !cell.valid ? 'bg-red-50 text-red-700' : 'text-gray-700'}`}
                          title={cell?.errorMessage ?? undefined}
                        >
                          {cell?.value || '-'}
                        </td>
                      );
                    })}
                    <td className="py-1.5">
                      {row.createdOrderId ? (
                        <span className="text-blue-700">주문 #{row.createdOrderId}</span>
                      ) : row.valid ? (
                        <span className="text-emerald-600">통과</span>
                      ) : (
                        <span className="text-red-600">{row.errorMessage}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={busy || selected.status === 'CONFIRMED' || selected.status === 'DISCARDED'}
              onClick={() => revalidate(selected.id)}
              className="px-3 py-2 text-xs font-semibold rounded-lg border border-gray-200 text-gray-700 hover:bg-gray-50 disabled:opacity-40"
            >
              재검증
            </button>
            <button
              type="button"
              disabled={busy || selected.status !== 'VALIDATED'}
              onClick={() => confirm(selected.id)}
              className="px-3 py-2 text-xs font-semibold rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-40"
              title={selected.status !== 'VALIDATED' ? '전 행이 검증을 통과해야 전환할 수 있습니다.' : undefined}
            >
              실주문 전환
            </button>
            <button
              type="button"
              disabled={busy || selected.status === 'CONFIRMED' || selected.status === 'DISCARDED'}
              onClick={() => discard(selected.id)}
              className="px-3 py-2 text-xs font-semibold rounded-lg border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-40"
            >
              폐기
            </button>
          </div>

          {confirmResult && (
            <div className="rounded-lg bg-gray-50 border border-gray-200 p-3 text-xs text-gray-700">
              생성 {confirmResult.created}건 · 실패 {confirmResult.failed}건
              {confirmResult.failed > 0 && (
                <p className="mt-1 text-red-600">
                  실패한 행만 고쳐 다시 전환하면 됩니다 — 이미 나간 주문은 다시 만들지 않습니다.
                </p>
              )}
            </div>
          )}
        </section>
      )}
    </div>
  );
};

export default BulkOrderPage;
