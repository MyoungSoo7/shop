import React from 'react';
import type { Facet, FacetSelection } from '@/api/facet';
import { countSelected } from '@/api/facet';

interface Props {
  facets: Facet[];
  selection: FacetSelection;
  onToggle: (axisCode: string, valueCode: string) => void;
  onClear: () => void;
  loading?: boolean;
}

/**
 * 옵션 파셋 필터 — 축별 체크박스.
 *
 * <p>표시 규칙 세 가지가 이 컴포넌트의 전부다:
 * <ul>
 *   <li><b>개수는 서버가 준 것을 그대로 쓴다.</b> "이 값을 (추가로) 고르면 남는 상품 수" 이며,
 *       화면이 다시 계산하지 않는다 — 축 간 AND 를 SKU 단위로 판정하는 규칙은 서버에만 있다.</li>
 *   <li><b>0 건은 고를 수 없다.</b> 눌러도 빈 결과가 나오는 선택지를 살려 두면 사용자가 헛클릭한다.
 *       단, 이미 고른 값은 0 이어도 끌 수 있어야 하므로 잠그지 않는다(아니면 되돌릴 길이 막힌다).</li>
 *   <li><b>체크 상태도 서버 판단({@code selected})을 따른다.</b> 화면이 따로 들고 있으면
 *       응답과 어긋나는 순간 어느 쪽이 맞는지 알 수 없다.</li>
 * </ul>
 */
const OptionFacetPanel: React.FC<Props> = ({ facets, selection, onToggle, onClear, loading }) => {
  if (facets.length === 0) {
    return null;
  }

  const selectedCount = countSelected(selection);

  return (
    <section aria-label="옵션 필터" className="border border-gray-200 rounded-lg p-4 space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-800">
          옵션 필터
          {selectedCount > 0 && (
            <span className="ml-2 text-xs font-normal text-blue-600">{selectedCount}개 선택</span>
          )}
        </h3>
        {selectedCount > 0 && (
          <button
            type="button"
            onClick={onClear}
            className="text-xs text-gray-500 hover:text-gray-700 underline"
          >
            초기화
          </button>
        )}
      </div>

      {facets.map((facet) => (
        <fieldset key={facet.axisCode} className="space-y-1.5">
          <legend className="text-xs font-medium text-gray-600 mb-1">{facet.axisName}</legend>
          <div className="flex flex-wrap gap-x-4 gap-y-1.5">
            {facet.values.map((value) => {
              // 0 건이어도 이미 고른 값은 끌 수 있어야 한다 — 아니면 되돌릴 길이 막힌다.
              const disabled = value.productCount === 0 && !value.selected;
              return (
                <label
                  key={value.code}
                  className={`flex items-center gap-1.5 text-sm ${
                    disabled ? 'text-gray-300 cursor-not-allowed' : 'text-gray-700 cursor-pointer'
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={value.selected}
                    disabled={disabled || loading}
                    onChange={() => onToggle(facet.axisCode, value.code)}
                    className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                  />
                  <span>{value.name}</span>
                  <span className="text-xs text-gray-400">({value.productCount})</span>
                </label>
              );
            })}
          </div>
        </fieldset>
      ))}
    </section>
  );
};

export default OptionFacetPanel;
