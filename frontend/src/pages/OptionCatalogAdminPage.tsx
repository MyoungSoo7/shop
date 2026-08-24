import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  optionCatalogApi,
  type OptionAxis,
  type OptionAxisValue,
  type OptionInputType,
} from '@/api/optionCatalog';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 표준 옵션 축·값 카탈로그 관리 콘솔.
 *
 * <p>지금까지 축을 하나 늘리려면 시드 마이그레이션이 필요했다 — 배포 없이는 "용량" 축을 만들 수
 * 없었다는 뜻이다. 이 화면이 그 자리를 연다.
 *
 * <p>코드는 만든 뒤 바꾸지 못한다. SKU 조합 서명이 축·값 <b>id</b> 로 묶여 있고 코드는 그 id 를
 * 사람이 부르는 이름표라, 코드를 갈아치우면 이름표만 바뀐 채 매핑이 남는다. 그래서 수정 폼은
 * 이름·표시색·정렬만 연다.
 */

const INPUT_TYPE_LABEL: Record<OptionInputType, string> = {
  SELECT: '드롭다운 (SELECT)',
  SWATCH: '색상 칩 (SWATCH)',
  TEXT: '직접 입력 (TEXT)',
};

const blankOrNull = (value: string) => (value.trim() === '' ? null : value.trim());

const OptionCatalogAdminPage: React.FC = () => {
  const { showToast } = useToast();

  const [axes, setAxes] = useState<OptionAxis[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [values, setValues] = useState<OptionAxisValue[]>([]);
  const [valuesLoading, setValuesLoading] = useState(false);

  // 새 축 입력
  const [axisCode, setAxisCode] = useState('');
  const [axisName, setAxisName] = useState('');
  const [axisInputType, setAxisInputType] = useState<OptionInputType>('SELECT');

  // 값 입력 (editingCode 가 있으면 수정 모드)
  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [valueCode, setValueCode] = useState('');
  const [valueName, setValueName] = useState('');
  const [valueSwatch, setValueSwatch] = useState('');
  const [valueSortOrder, setValueSortOrder] = useState('');

  const loadAxes = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setAxes(await optionCatalogApi.listAxes());
    } catch (err) {
      setAxes([]);
      setError(apiErrorMessage(err, '옵션 축을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadAxes(); }, [loadAxes]);

  const selected = useMemo(
    () => axes.find((a) => a.code === selectedCode) ?? null,
    [axes, selectedCode],
  );

  const loadValues = useCallback(async (code: string) => {
    setValuesLoading(true);
    try {
      setValues(await optionCatalogApi.listValues(code));
    } catch (err) {
      setValues([]);
      showToast(apiErrorMessage(err, '옵션 값을 불러오지 못했습니다.'), 'error');
    } finally {
      setValuesLoading(false);
    }
  }, [showToast]);

  const resetValueForm = useCallback(() => {
    setEditingCode(null);
    setValueCode(''); setValueName(''); setValueSwatch(''); setValueSortOrder('');
  }, []);

  const openAxis = useCallback(async (axis: OptionAxis) => {
    setSelectedCode(axis.code);
    resetValueForm();
    await loadValues(axis.code);
  }, [loadValues, resetValueForm]);

  const createAxis = useCallback(async () => {
    if (axisCode.trim() === '' || axisName.trim() === '') {
      showToast('축 코드와 이름은 필수입니다.', 'warning');
      return;
    }
    try {
      await optionCatalogApi.createAxis({
        code: axisCode.trim(), name: axisName.trim(), inputType: axisInputType,
      });
      showToast(`옵션 축 ${axisCode.trim()} 을(를) 만들었습니다.`, 'success');
      setAxisCode(''); setAxisName('');
      await loadAxes();
    } catch (err) {
      showToast(apiErrorMessage(err, '옵션 축을 만들지 못했습니다.'), 'error');
    }
  }, [axisCode, axisName, axisInputType, loadAxes, showToast]);

  const toggleAxis = useCallback(async (axis: OptionAxis) => {
    try {
      await optionCatalogApi.setAxisActive(axis.code, !axis.active);
      await loadAxes();
    } catch (err) {
      showToast(apiErrorMessage(err, '축 상태를 바꾸지 못했습니다.'), 'error');
    }
  }, [loadAxes, showToast]);

  const startEdit = useCallback((value: OptionAxisValue) => {
    setEditingCode(value.code);
    setValueCode(value.code);
    setValueName(value.name);
    setValueSwatch(value.swatchHex ?? '');
    setValueSortOrder(String(value.sortOrder));
  }, []);

  const submitValue = useCallback(async () => {
    if (!selected) return;
    const trimmedCode = valueCode.trim();
    if (trimmedCode === '' || valueName.trim() === '') {
      showToast('값 코드와 이름은 필수입니다.', 'warning');
      return;
    }
    // 서버 도메인도 막지만, 왕복 없이 즉시 알려 준다 — SWATCH 축의 값은 표시색이 있어야 칩이 그려진다
    if (selected.inputType === 'SWATCH' && blankOrNull(valueSwatch) === null) {
      showToast('SWATCH 축의 값에는 표시색(#RRGGBB)이 필요합니다.', 'warning');
      return;
    }
    const payload = {
      name: valueName.trim(),
      swatchHex: blankOrNull(valueSwatch),
      sortOrder: valueSortOrder.trim() === '' ? 0 : Number(valueSortOrder),
    };
    try {
      if (editingCode) {
        await optionCatalogApi.updateValue(selected.code, editingCode, payload);
      } else {
        await optionCatalogApi.addValue(selected.code, { code: trimmedCode, ...payload });
      }
      resetValueForm();
      await loadValues(selected.code);
    } catch (err) {
      showToast(apiErrorMessage(err, '옵션 값을 저장하지 못했습니다.'), 'error');
    }
  }, [selected, valueCode, valueName, valueSwatch, valueSortOrder, editingCode,
    loadValues, resetValueForm, showToast]);

  const toggleValue = useCallback(async (value: OptionAxisValue) => {
    if (!selected) return;
    try {
      await optionCatalogApi.setValueActive(selected.code, value.code, !value.active);
      await loadValues(selected.code);
    } catch (err) {
      showToast(apiErrorMessage(err, '값 상태를 바꾸지 못했습니다.'), 'error');
    }
  }, [selected, loadValues, showToast]);

  /** 축이 SWATCH 인데 표시색이 빈 값들 — 그대로 두면 구매 화면이 빈 칩을 그린다 */
  const swatchless = useMemo(() => {
    if (selected?.inputType !== 'SWATCH') return [];
    return values.filter((v) => !v.swatchHex).map((v) => v.code);
  }, [selected, values]);

  const enumerated = selected !== null && selected.inputType !== 'TEXT';

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-xl font-bold text-gray-900">옵션 카탈로그</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          상품 간 재사용되는 표준 축과 값입니다. 축이 상품마다 따로 생기면 "사이즈" 가 판매자별로
          갈려 파셋 검색·통계가 성립하지 않습니다. 코드는 만든 뒤 바꿀 수 없고(수정은 이름·표시색·정렬만),
          SKU 조합은 코드가 아니라 id 로 묶여 있어 이름을 바꿔도 재고·주문은 흔들리지 않습니다.
        </p>
      </div>

      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <h3 className="font-bold text-gray-900 text-sm mb-3">새 표준 축</h3>
        <div className="flex flex-wrap items-end gap-3">
          <label className="block">
            <span className="text-[11px] text-gray-500">축 코드</span>
            <input
              value={axisCode}
              onChange={(e) => setAxisCode(e.target.value.toUpperCase())}
              placeholder="CAPACITY"
              className="input w-44 font-mono"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">축 이름</span>
            <input
              value={axisName}
              onChange={(e) => setAxisName(e.target.value)}
              placeholder="용량"
              className="input w-44"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">표현 방식</span>
            <select
              value={axisInputType}
              onChange={(e) => setAxisInputType(e.target.value as OptionInputType)}
              className="input w-48"
            >
              {(Object.keys(INPUT_TYPE_LABEL) as OptionInputType[]).map((t) => (
                <option key={t} value={t}>{INPUT_TYPE_LABEL[t]}</option>
              ))}
            </select>
          </label>
          <button
            onClick={() => void createAxis()}
            className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800"
          >
            축 만들기
          </button>
        </div>
        <p className="text-[11px] text-gray-400 mt-2">
          코드에는 공백과 <span className="font-mono">:</span> <span className="font-mono">/</span> 를 쓸 수
          없습니다 — 두 글자가 표시용 라벨(색상:빨강/사이즈:L)의 구분자입니다.
        </p>
      </section>

      {loading && <div className="flex justify-center py-6"><Spinner /></div>}
      {error && (
        <p className="text-sm text-orange-700 bg-orange-50 border border-orange-100 rounded px-3 py-2 mb-6">{error}</p>
      )}

      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <h3 className="font-bold text-gray-900 text-sm mb-3">표준 축 {axes.length}종</h3>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {['코드', '이름', '표현 방식', '상태', ''].map((h) => (
                  <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500 whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {axes.map((axis) => (
                <tr key={axis.code} className={selectedCode === axis.code ? 'bg-gray-50' : ''}>
                  <td className="px-3 py-2 font-mono text-xs">{axis.code}</td>
                  <td className="px-3 py-2 font-medium text-gray-900">{axis.name}</td>
                  <td className="px-3 py-2 text-gray-600 whitespace-nowrap">{INPUT_TYPE_LABEL[axis.inputType]}</td>
                  <td className="px-3 py-2">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${
                      axis.active ? 'bg-green-100 text-green-800' : 'bg-gray-200 text-gray-600'
                    }`}>
                      {axis.active ? '사용' : '내림'}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right whitespace-nowrap">
                    <button
                      onClick={() => void openAxis(axis)}
                      className="px-2 py-1 rounded border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50"
                    >
                      값 보기
                    </button>
                    <button
                      onClick={() => void toggleAxis(axis)}
                      className="ml-2 px-2 py-1 rounded border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50"
                    >
                      {axis.active ? '내리기' : '올리기'}
                    </button>
                  </td>
                </tr>
              ))}
              {!loading && axes.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-3 py-8 text-center text-gray-400">
                    등록된 표준 축이 없습니다.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {selected && (
        <section className="bg-white rounded-xl border border-gray-200 p-4">
          <h3 className="font-bold text-gray-900 text-sm mb-1">
            {selected.name} 축의 표준 값 <span className="font-mono text-xs text-gray-400">{selected.code}</span>
          </h3>
          <p className="text-[11px] text-gray-400 mb-3">
            값을 내려도 이미 그 값을 파는 상품의 판매를 멈추지 않습니다 — 상품이 실제로 파는 값은 상품별
            채택 목록이 들고 있고, 여기서 내리는 것은 "앞으로 새로 채택하지 말라" 는 표시입니다.
          </p>

          {swatchless.length > 0 && (
            <p className="text-sm text-orange-700 bg-orange-50 border border-orange-100 rounded px-3 py-2 mb-3">
              표시색이 없는 값이 있습니다 — SWATCH 축이라 구매 화면이 빈 칩을 그립니다: {swatchless.join(', ')}
            </p>
          )}

          {!enumerated ? (
            <p className="text-sm text-gray-500 bg-gray-50 border border-gray-100 rounded px-3 py-2">
              직접 입력(TEXT) 축은 표준값 목록을 갖지 않습니다 — 각인 문구처럼 구매자가 그때그때 적는 축입니다.
            </p>
          ) : (
            <div className="flex flex-wrap items-end gap-3 mb-4">
              <label className="block">
                <span className="text-[11px] text-gray-500">값 코드</span>
                <input
                  value={valueCode}
                  onChange={(e) => setValueCode(e.target.value.toUpperCase())}
                  disabled={editingCode !== null}
                  placeholder="RED"
                  className="input w-32 font-mono disabled:bg-gray-50 disabled:text-gray-400"
                />
              </label>
              <label className="block">
                <span className="text-[11px] text-gray-500">값 이름</span>
                <input
                  value={valueName}
                  onChange={(e) => setValueName(e.target.value)}
                  placeholder="빨강"
                  className="input w-36"
                />
              </label>
              <label className="block">
                <span className="text-[11px] text-gray-500">표시색</span>
                <input
                  value={valueSwatch}
                  onChange={(e) => setValueSwatch(e.target.value)}
                  placeholder={selected.inputType === 'SWATCH' ? '#FF0000 (필수)' : '#RRGGBB'}
                  className="input w-32 font-mono"
                />
              </label>
              <label className="block">
                <span className="text-[11px] text-gray-500">정렬</span>
                <input
                  value={valueSortOrder}
                  onChange={(e) => setValueSortOrder(e.target.value)}
                  placeholder="0"
                  inputMode="numeric"
                  className="input w-20 font-mono"
                />
              </label>
              <button
                onClick={() => void submitValue()}
                className="px-3 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800"
              >
                {editingCode ? '값 저장' : '값 추가'}
              </button>
              {editingCode && (
                <button
                  onClick={resetValueForm}
                  className="px-3 py-2 rounded-lg border border-gray-200 text-sm font-semibold text-gray-700 hover:bg-gray-50"
                >
                  취소
                </button>
              )}
            </div>
          )}

          {valuesLoading && <div className="flex justify-center py-4"><Spinner /></div>}

          {enumerated && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    {['코드', '이름', '표시색', '정렬', '상태', ''].map((h) => (
                      <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {values.map((value) => (
                    <tr key={value.code} data-testid="axis-value-row"
                        className={editingCode === value.code ? 'bg-gray-50' : ''}>
                      <td className="px-3 py-2 font-mono text-xs">{value.code}</td>
                      <td className="px-3 py-2 text-gray-900">{value.name}</td>
                      <td className="px-3 py-2">
                        {value.swatchHex ? (
                          <span className="inline-flex items-center gap-2">
                            <span
                              className="inline-block w-4 h-4 rounded-full border border-gray-200"
                              style={{ backgroundColor: value.swatchHex }}
                            />
                            <span className="font-mono text-xs text-gray-500">{value.swatchHex}</span>
                          </span>
                        ) : (
                          <span className="text-xs text-gray-400">-</span>
                        )}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs text-gray-500">{value.sortOrder}</td>
                      <td className="px-3 py-2">
                        <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${
                          value.active ? 'bg-green-100 text-green-800' : 'bg-gray-200 text-gray-600'
                        }`}>
                          {value.active ? '사용' : '내림'}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-right whitespace-nowrap">
                        <button
                          onClick={() => startEdit(value)}
                          className="px-2 py-1 rounded border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50"
                        >
                          수정
                        </button>
                        <button
                          onClick={() => void toggleValue(value)}
                          className="ml-2 px-2 py-1 rounded border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50"
                        >
                          {value.active ? '내리기' : '올리기'}
                        </button>
                      </td>
                    </tr>
                  ))}
                  {!valuesLoading && values.length === 0 && (
                    <tr>
                      <td colSpan={6} className="px-3 py-8 text-center text-gray-400">
                        이 축에는 아직 표준 값이 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
};

export default OptionCatalogAdminPage;
