import React, { useCallback, useState } from 'react';
import {
  categoryIntegrityApi,
  type CategoryCountIntegrity,
} from '@/api/categoryIntegrity';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 카테고리 상품수 캐시 정합 점검 패널.
 *
 * <p>트리에 붙는 상품수는 캐시다. 캐시를 둔 이유는 뱃지를 달 때마다 세기 비싸서인데, 갱신을 한 번
 * 빠뜨리면 조용히 틀린다 — 화면은 여전히 숫자를 보여 주므로 아무도 모른다.
 *
 * <p>세는 일과 고치는 일을 갈라 둔다. 점검은 읽기 전용이고, 재계산은 누른 사람이 있는 행위다.
 * 재계산 뒤에는 "고쳤습니다" 대신 <b>다시 센 숫자</b>를 보여 준다 — 고쳤다는 말은 근거가 아니다.
 */

const KIND_LABEL: Record<string, string> = {
  OVERCOUNT: '과다',
  UNDERCOUNT: '과소',
};

const CategoryCountIntegrityPanel: React.FC = () => {
  const { showToast } = useToast();

  const [report, setReport] = useState<CategoryCountIntegrity | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const check = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await categoryIntegrityApi.checkCounts());
    } catch (err) {
      setReport(null);
      setError(apiErrorMessage(err, '상품수 정합을 점검하지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const updated = await categoryIntegrityApi.refreshCounts();
      showToast(`${updated}개 카테고리의 상품수 캐시를 다시 채웠습니다.`, 'success');
      setReport(await categoryIntegrityApi.checkCounts());
      setError(null);
    } catch (err) {
      setError(apiErrorMessage(err, '상품수 캐시를 재계산하지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
      <div className="flex flex-wrap items-center gap-3">
        <div>
          <h3 className="font-bold text-gray-900 text-sm">상품수 캐시 정합</h3>
          <p className="text-[11px] text-gray-500 mt-0.5">
            트리에 보이는 상품수는 캐시입니다. 정본은 상품↔카테고리 매핑의 실계수이고, 갱신을 빠뜨리면
            숫자만 조용히 틀립니다.
          </p>
        </div>
        <div className="ml-auto flex items-center gap-2">
          <button
            onClick={() => void check()}
            disabled={loading}
            className="px-3 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-40"
          >
            상품수 점검
          </button>
          {report && !report.healthy && (
            <button
              onClick={() => void refresh()}
              disabled={loading}
              className="px-3 py-2 rounded-lg border border-gray-200 text-sm font-semibold text-gray-700 hover:bg-gray-50 disabled:opacity-40"
            >
              재계산
            </button>
          )}
        </div>
      </div>

      {loading && <div className="flex justify-center py-4"><Spinner /></div>}
      {error && <p className="text-sm text-orange-700 mt-3">{error}</p>}

      {report && !loading && (
        <div className="mt-3">
          {report.healthy ? (
            <p className="text-sm text-green-800 bg-green-50 border border-green-100 rounded px-3 py-2">
              캐시와 실계수가 일치합니다.
            </p>
          ) : (
            <>
              <p className="text-sm text-orange-800 bg-orange-50 border border-orange-100 rounded px-3 py-2">
                상품수가 어긋난 카테고리 {report.drifted}건
                {Object.keys(report.byKind).length > 0 && (
                  <>
                    {' — '}
                    {Object.entries(report.byKind)
                      .map(([kind, count]) => `${KIND_LABEL[kind] ?? kind} ${count}건`)
                      .join(' · ')}
                  </>
                )}
                {report.samples.length > 0 && report.samples.length < report.drifted && (
                  <span className="text-[11px] text-orange-700"> (표본 {report.samples.length}건만 표시)</span>
                )}
                {report.samples.length > 0 && report.samples.length >= report.drifted && (
                  <span className="text-[11px] text-orange-700"> (표본 {report.samples.length}건)</span>
                )}
              </p>

              {report.unreadable > 0 && (
                <p className="text-sm text-red-800 bg-red-50 border border-red-100 rounded px-3 py-2 mt-2">
                  드리프트로 인정되지 않은 행 {report.unreadable}건 — 점검 조회 조건이 재계산과 갈렸는지
                  확인하세요.
                </p>
              )}

              <div className="overflow-x-auto mt-3">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 border-b border-gray-200">
                    <tr>
                      {['카테고리', 'slug', '캐시', '실계수', '차이', '방향'].map((h) => (
                        <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {report.samples.map((sample) => (
                      <tr key={sample.categoryId} data-testid="count-drift-row">
                        <td className="px-3 py-2 text-gray-900">{sample.name}</td>
                        <td className="px-3 py-2 font-mono text-xs text-gray-500">{sample.slug}</td>
                        <td className="px-3 py-2 font-mono text-xs text-right">{sample.cachedCount}</td>
                        <td className="px-3 py-2 font-mono text-xs text-right">{sample.actualCount}</td>
                        <td className={`px-3 py-2 font-mono text-xs text-right font-semibold ${
                          sample.difference > 0 ? 'text-red-700' : 'text-blue-700'
                        }`}>
                          {sample.difference > 0 ? `+${sample.difference}` : sample.difference}
                        </td>
                        <td className="px-3 py-2">
                          <span className="text-xs px-2 py-0.5 rounded-full font-semibold bg-gray-100 text-gray-700">
                            {KIND_LABEL[sample.kind] ?? sample.kind}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}
    </section>
  );
};

export default CategoryCountIntegrityPanel;
