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
  /** 자유입력 축코드 → 구매자가 적은 문구. 선택형과 저장소를 나눈다(코드가 아니라 문장이라서). */
  texts?: Record<string, string>;
  onTextsChange?: (texts: Record<string, string>) => void;
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
 *   <li>TEXT 축(각인 문구처럼 직접 적는 축)은 입력칸으로 그린다. 이 축은 <b>SKU 를 만들지 않으므로</b>
 *       조합 판정(품절·추가금)에 끼지 않는다 — 문구가 달라도 재고는 같은 칸에서 빠진다.</li>
 * </ul>
 */
const ProductOptionSelector: React.FC<Props> = ({
  productId,
  selection,
  onChange,
  texts = {},
  onTextsChange,
  onReadyChange,
}) => {
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

  // 선택형(칩) 축과 자유입력(입력칸) 축은 그리는 법도, 준비됐는지 따지는 법도 다르다.
  // 자유입력 축은 값 목록이 비어 오므로 textMaxLength 로 가른다.
  const axes = useMemo(
    () => (options?.axes ?? []).filter((a) => a.values.length > 0),
    [options]
  );
  const textAxes = useMemo(
    () => (options?.axes ?? []).filter((a) => a.textMaxLength != null),
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

  // 필수 자유입력은 비어 있으면 안 된다. 서버도 같은 것을 검사하지만, 결제까지 간 뒤
  // 거절당하는 것보다 버튼이 안 눌리는 편이 낫다.
  const textsReady = textAxes.every((a) => !a.required || (texts[a.code] ?? '').trim().length > 0);
  const selectionReady = axes.length === 0 ? true : matched?.available === true;
  const ready = selectionReady && textsReady;

  useEffect(() => {
    onReadyChange?.(ready);
  }, [ready, onReadyChange]);

  if (loading) {
    return <p className="text-xs text-gray-400">옵션 불러오는 중...</p>;
  }
  if (error) {
    return <p className="text-xs text-red-500">{error}</p>;
  }
  if (axes.length === 0 && textAxes.length === 0) {
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

  const missing = [
    ...axes.filter((a) => a.required && !selection[a.code]),
    ...textAxes.filter((a) => a.required && (texts[a.code] ?? '').trim().length === 0),
  ].map((a) => a.name);

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

      {textAxes.map((axis) => {
        const value = texts[axis.code] ?? '';
        const limit = axis.textMaxLength ?? 200;
        return (
          <div key={axis.code} className="space-y-1.5">
            <label htmlFor={`opt-text-${axis.code}`} className="block text-xs font-medium text-gray-600">
              {axis.name}
              {axis.required && <span className="ml-1 text-red-500">*</span>}
            </label>
            <input
              id={`opt-text-${axis.code}`}
              type="text"
              value={value}
              maxLength={limit}
              placeholder={`${limit}자까지 입력할 수 있습니다`}
              onChange={(e) => onTextsChange?.({ ...texts, [axis.code]: e.target.value })}
              className="w-full px-3 py-2 rounded-lg border border-gray-300 text-sm focus:border-blue-400 focus:outline-none"
            />
            <p className="text-xs text-gray-400 text-right">
              {value.length} / {limit}
            </p>
          </div>
        );
      })}

      {/* 조합 판정은 선택형 축이 있을 때만 뜻이 있다. 자유입력만 있는 상품은 고를 조합이
          없으므로 matched 가 늘 undefined 인데, 그걸 "판매하지 않는 조합"으로 읽으면 안 된다. */}
      {missing.length > 0 ? (
        <p className="text-xs text-gray-500">선택이 필요합니다: {missing.join(', ')}</p>
      ) : axes.length === 0 ? null : matched === undefined ? (
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
