import React, { useEffect, useMemo, useState } from 'react';
import {
  productOptionApi,
  matchCombination,
  unavailableValueCodes,
  type ProductOptions,
} from '@/api/productOptions';

interface Props {
  productId: number;
  /** 축코드 → 값코드. 부모가 들고 있어야 장바구니·주문이 같은 선택을 쓴다. */
  selection: Record<string, string>;
  onChange: (selection: Record<string, string>) => void;
  /** 필수 축을 모두 골랐는지 + 그 조합이 실제로 살 수 있는지. 부모의 버튼 활성 조건. */
  onReadyChange?: (ready: boolean) => void;
}

/**
 * 구매자 옵션 선택기 — 축마다 값 하나.
 *
 * <p><b>왜 생겼나.</b> 옵션 축·값·SKU 는 이미 다 있었는데 <b>고르는 화면</b>만 없었다. 그래서
 * 주문 라인은 늘 {@code { productId, quantity }} 로만 만들어졌고, SKU 를 가리키는 주문이 한 건도
 * 없었다. 재고는 SKU 에 붙어 있으므로, 옵션 없는 주문만 나가는 몰은 <b>재고를 깎지 않는 몰</b>이다.
 *
 * <p>표시 규칙:
 * <ul>
 *   <li>축은 차수(sortOrder) 순으로 그린다 — 1차·2차·3차의 순서가 서버 정본이다.</li>
 *   <li>지금 선택과 함께 살 수 있는 조합이 없는 값은 회색으로 죽인다. 이건 <b>표시</b> 판정이라
 *       틀려도 서버가 막는다. 실제 SKU 해석은 주문 시점에 resolve 가 다시 한다.</li>
 *   <li>이미 고른 값은 다시 눌러 끌 수 있다 — 아니면 조합을 바꿀 길이 막힌다.</li>
 *   <li>TEXT 축(각인 문구처럼 직접 입력하는 축)은 아직 값 목록이 없어 그리지 않는다. 그 경로는
 *       스키마·주문 스냅샷까지 함께 열어야 성립한다.</li>
 * </ul>
 */
const ProductOptionSelector: React.FC<Props> = ({ productId, selection, onChange, onReadyChange }) => {
  const [options, setOptions] = useState<ProductOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    productOptionApi
      .describe(productId)
      .then((data) => {
        if (alive) setOptions(data);
      })
      .catch(() => {
        if (alive) setError('옵션을 불러오지 못했습니다.');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [productId]);

  // 값 목록이 있는 축만 고를 수 있다. TEXT 축은 values 가 비어 오므로 자연히 빠진다.
  const axes = useMemo(
    () => (options?.axes ?? []).filter((a) => a.values.length > 0),
    [options]
  );

  const matched = useMemo(
    () => (options ? matchCombination(options.combinations, selection) : undefined),
    [options, selection]
  );

  const dead = useMemo(
    () => (options ? unavailableValueCodes(options.combinations, axes, selection) : {}),
    [options, axes, selection]
  );

  const ready = axes.length === 0 ? true : matched?.available === true;

  useEffect(() => {
    onReadyChange?.(ready);
  }, [ready, onReadyChange]);

  if (loading) {
    return <p className="text-xs text-gray-400">옵션 불러오는 중...</p>;
  }
  if (error) {
    return <p className="text-xs text-red-500">{error}</p>;
  }
  if (axes.length === 0) {
    return null; // 옵션 없는 상품 — 고를 것이 없으면 칸도 내지 않는다
  }

  const toggle = (axisCode: string, valueCode: string) => {
    const next = { ...selection };
    if (next[axisCode] === valueCode) {
      delete next[axisCode];
    } else {
      next[axisCode] = valueCode;
    }
    onChange(next);
  };

  const missing = axes.filter((a) => a.required && !selection[a.code]).map((a) => a.name);

  return (
    <section aria-label="옵션 선택" className="border border-gray-200 rounded-lg p-4 space-y-4">
      <h3 className="text-sm font-semibold text-gray-800">옵션 선택</h3>

      {axes.map((axis) => (
        <fieldset key={axis.code} className="space-y-1.5">
          <legend className="text-xs font-medium text-gray-600 mb-1">
            {axis.name}
            {axis.required && <span className="ml-1 text-red-500">*</span>}
          </legend>
          <div className="flex flex-wrap gap-2">
            {axis.values.map((value) => {
              const selected = selection[axis.code] === value.code;
              // 고른 값은 품절이어도 끌 수 있게 남긴다 — 아니면 되돌릴 길이 막힌다.
              const disabled = !selected && dead[axis.code]?.has(value.code);
              return (
                <button
                  key={value.code}
                  type="button"
                  disabled={disabled}
                  onClick={() => toggle(axis.code, value.code)}
                  aria-pressed={selected}
                  title={disabled ? '이 조합은 품절입니다' : value.name}
                  className={`tap-target px-3 py-1.5 rounded-lg border text-sm transition-colors flex items-center gap-1.5 ${
                    selected
                      ? 'border-blue-500 bg-blue-50 text-blue-700 font-medium'
                      : disabled
                        ? 'border-gray-200 text-gray-300 line-through cursor-not-allowed'
                        : 'border-gray-300 text-gray-700 hover:border-blue-400 hover:bg-blue-50'
                  }`}
                >
                  {axis.inputType === 'SWATCH' && value.swatchHex && (
                    <span
                      aria-hidden="true"
                      className="w-3.5 h-3.5 rounded-full border border-gray-300 flex-shrink-0"
                      style={{ backgroundColor: value.swatchHex }}
                    />
                  )}
                  {value.name}
                </button>
              );
            })}
          </div>
        </fieldset>
      ))}

      {missing.length > 0 ? (
        <p className="text-xs text-gray-500">선택이 필요합니다: {missing.join(', ')}</p>
      ) : matched === undefined ? (
        <p className="text-xs text-amber-600">판매하지 않는 조합입니다.</p>
      ) : !matched.available ? (
        <p className="text-xs text-amber-600">품절된 조합입니다.</p>
      ) : matched.additionalPrice ? (
        <p className="text-xs text-gray-600">
          옵션 추가금 {matched.additionalPrice > 0 ? '+' : ''}
          {matched.additionalPrice.toLocaleString()}원
        </p>
      ) : null}
    </section>
  );
};

export default ProductOptionSelector;
