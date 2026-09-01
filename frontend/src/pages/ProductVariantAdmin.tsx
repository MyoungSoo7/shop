import React, { useCallback, useEffect, useState } from 'react';
import {
  effectivePrice,
  productVariantApi,
  variantCostApi,
  type OptionSelection,
  type ProductVariant,
  type VariantCost,
} from '@/api/productVariant';
import { productApi } from '@/api/product';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';
import type { ProductResponse } from '@/types';

/**
 * 상품 옵션(SKU) 관리 콘솔.
 *
 * <p><b>왜 생겼나.</b> 옵션 축과 값을 정의하는 콘솔은 있었다(옵션 카탈로그). 그건 "색상에는
 * 빨강·파랑이 있다"는 <b>사전</b>이다. 하지만 "이 상품의 빨강/L 은 재고 3개, 추가금 2,000원"
 * 이라는 <b>실물</b>을 만드는 화면은 없었다 — SKU 를 만드는 길이 {@code curl} 뿐이었고, 재고가
 * 어긋나면 DB 를 직접 고쳤다. 그래서 이 화면은 옵션 카탈로그의 하위 화면이 아니라 별개다.
 *
 * <p><b>재고 차감에 확인 단계를 두는 이유.</b> 이 버튼은 주문 없이 재고를 줄인다. 되돌리는 API 는
 * 없고, 늘리려면 SKU 를 다시 만드는 수밖에 없다. 그래서 삭제 확인과 같은 방식으로 <b>무엇을
 * 얼마나</b> 줄이는지 다시 적어 보여 준다 — 버튼만 두 번 누르게 하면 두 번째 클릭은 첫 번째의
 * 연장이 되어 아무것도 막지 못한다.
 *
 * <p><b>옵션 해석 도구를 같이 둔다.</b> 주문이 선택 경로를 SKU 로 바꾸는 바로 그 경로를
 * 운영자가 눌러 볼 수 있어야, "고객이 빨강/L 을 고르면 무엇이 나가는가"를 주문을 넣어 보지
 * 않고 확인할 수 있다. 읽기만 하는 조회라 확인 단계가 없다.
 *
 * <p><b>매입가·마진은 별도 표에 둔다.</b> 위 옵션 목록은 구매자 화면과 같은 응답
 * ({@code VariantResponse})을 그리는데, 원가는 그 응답에 실리지 않는다 — 실으면 구매자가
 * 주문할 때 부르는 해석 경로로도 함께 나간다. 서버가 응답을 갈라 놓았으므로 화면도 표를 나눈다.
 * 한 표에 합치면 "이 숫자는 구매자에게 안 나간다"는 사실이 화면에서 사라진다.
 */

const formatMoney = (value: number | null | undefined) =>
  value == null ? '—' : `${Number(value).toLocaleString('ko-KR')}원`;

/** 마진율은 0 도 의미 있는 값이라 {@code ??} 가 아니라 null 비교로 갈라야 한다. */
const formatRate = (value: number | null | undefined) =>
  value == null ? '—' : `${Number(value).toFixed(2)}%`;

interface CreateForm {
  sku: string;
  optionName: string;
  additionalPrice: string;
  initialStock: string;
}

const EMPTY_CREATE: CreateForm = { sku: '', optionName: '', additionalPrice: '', initialStock: '0' };

const ProductVariantAdmin: React.FC = () => {
  const { showToast } = useToast();

  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [productId, setProductId] = useState<number | null>(null);
  const [loadingProducts, setLoadingProducts] = useState(true);

  const [variants, setVariants] = useState<ProductVariant[]>([]);
  const [loadingVariants, setLoadingVariants] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [form, setForm] = useState<CreateForm>(EMPTY_CREATE);

  // 재고 차감 — 확인 대기 중인 대상과 수량.
  const [decreaseTarget, setDecreaseTarget] = useState<ProductVariant | null>(null);
  const [decreaseQty, setDecreaseQty] = useState('1');

  // 매입가·마진. 입력칸은 SKU 별로 따로 들고 있어야 한 줄을 고치는 동안 다른 줄이 흔들리지 않는다.
  const [costs, setCosts] = useState<VariantCost[]>([]);
  const [costError, setCostError] = useState<string | null>(null);
  const [costDrafts, setCostDrafts] = useState<Record<number, string>>({});

  // 옵션 해석 도구.
  const [selections, setSelections] = useState<OptionSelection[]>([{ name: '', value: '' }]);
  const [resolved, setResolved] = useState<ProductVariant | null>(null);
  const [resolveError, setResolveError] = useState<string | null>(null);

  const selectedProduct = products.find((p) => p.id === productId) ?? null;

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await productApi.getAllProducts();
        if (!cancelled) setProducts(list);
      } catch (err) {
        if (!cancelled) setError(apiErrorMessage(err, '상품 목록을 불러오지 못했습니다.'));
      } finally {
        if (!cancelled) setLoadingProducts(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const loadVariants = useCallback(async (target: number) => {
    setLoadingVariants(true);
    setError(null);
    try {
      setVariants(await productVariantApi.list(target));
    } catch (err) {
      setError(apiErrorMessage(err, '옵션 목록을 불러오지 못했습니다.'));
      setVariants([]);
    } finally {
      setLoadingVariants(false);
    }
  }, []);

  /**
   * 원가 표는 옵션 목록과 별개로 읽는다. 경로가 다르고(관리자 전용) 권한도 다르다 —
   * 원가 조회가 막혀도 옵션 목록은 보여야 하므로 실패를 서로 옮기지 않는다.
   */
  const loadCosts = useCallback(async (target: number) => {
    setCostError(null);
    try {
      setCosts(await variantCostApi.list(target));
    } catch (err) {
      setCostError(apiErrorMessage(err, '매입가를 불러오지 못했습니다.'));
      setCosts([]);
    }
  }, []);

  useEffect(() => {
    if (productId === null) { setVariants([]); setCosts([]); return; }
    void loadVariants(productId);
    void loadCosts(productId);
    setDecreaseTarget(null);
    setResolved(null);
    setResolveError(null);
    setCostDrafts({});
  }, [productId, loadVariants, loadCosts]);

  const stock = Number(form.initialStock);
  const canCreate = productId !== null
    && form.sku.trim() !== ''
    && form.optionName.trim() !== ''
    && Number.isInteger(stock) && stock >= 0;

  const create = async () => {
    if (productId === null || !canCreate || busy) return;
    setBusy(true);
    try {
      const added = await productVariantApi.create(productId, {
        sku: form.sku.trim(),
        optionName: form.optionName.trim(),
        // 빈 칸은 0 이 아니라 "미설정"으로 보낸다 — 0 을 보내면 "추가금 없음"을 명시한 것이
        // 되어, 나중에 기본값 정책이 바뀌어도 이 SKU 만 옛 값에 고정된다.
        additionalPrice: form.additionalPrice.trim() === '' ? null : Number(form.additionalPrice),
        initialStock: stock,
      });
      setVariants((prev) => [...prev, added]);
      setForm(EMPTY_CREATE);
      showToast(`옵션 ${added.sku} 을(를) 만들었습니다.`, 'success');
    } catch (err) {
      showToast(apiErrorMessage(err, '옵션을 만들지 못했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const decrease = async () => {
    if (productId === null || decreaseTarget === null || busy) return;
    const quantity = Number(decreaseQty);
    if (!Number.isInteger(quantity) || quantity < 1) return;
    setBusy(true);
    try {
      const updated = await productVariantApi.decreaseStock(productId, decreaseTarget.id, quantity);
      setVariants((prev) => prev.map((v) => (v.id === updated.id ? updated : v)));
      setDecreaseTarget(null);
      setDecreaseQty('1');
      showToast(`${updated.sku} 재고를 ${quantity}개 줄였습니다.`, 'success');
    } catch (err) {
      showToast(apiErrorMessage(err, '재고를 줄이지 못했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  /**
   * 매입가 저장. 빈 칸은 0 이 아니라 {@code null} — "지운다"와 "0원에 샀다"는 다른 사실이고,
   * 빈 칸을 0 으로 보내면 마진 100% 짜리 SKU 가 조용히 생긴다.
   */
  const saveCost = async (cost: VariantCost) => {
    if (productId === null || busy) return;
    const raw = (costDrafts[cost.variantId] ?? '').trim();
    if (raw !== '' && !(Number.isFinite(Number(raw)) && Number(raw) >= 0)) {
      showToast('매입가는 0 이상의 숫자여야 합니다.', 'error');
      return;
    }
    const next = raw === '' ? null : Number(raw);
    setBusy(true);
    try {
      const updated = await variantCostApi.setPurchasePrice(productId, cost.variantId, next);
      setCosts((prev) => prev.map((c) => (c.variantId === updated.variantId ? updated : c)));
      setCostDrafts((prev) => {
        const rest = { ...prev };
        delete rest[cost.variantId];
        return rest;
      });
      showToast(
        next === null
          ? `${updated.sku} 매입가를 지웠습니다.`
          : `${updated.sku} 매입가를 저장했습니다.`,
        'success',
      );
    } catch (err) {
      showToast(apiErrorMessage(err, '매입가를 저장하지 못했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const resolve = async () => {
    if (productId === null || busy) return;
    const filled = selections.filter((s) => s.name.trim() !== '' && s.value.trim() !== '');
    if (filled.length === 0) return;
    setBusy(true);
    setResolveError(null);
    try {
      setResolved(await productVariantApi.resolve(productId, filled));
    } catch (err) {
      setResolved(null);
      setResolveError(apiErrorMessage(err, '해당하는 옵션을 찾지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const updateSelection = (index: number, patch: Partial<OptionSelection>) => {
    setSelections((prev) => prev.map((s, i) => (i === index ? { ...s, ...patch } : s)));
  };

  return (
    <main className="mx-auto max-w-5xl p-6 space-y-6">
      <header>
        <h1 className="text-2xl font-bold">상품 옵션(SKU) 관리</h1>
        <p className="text-sm text-gray-500">
          상품별 옵션 조합의 재고·추가금을 만들고 조정합니다. 재고 차감은 되돌릴 수 없습니다.
        </p>
      </header>

      {error && <p role="alert" className="text-red-600">{error}</p>}

      <section className="rounded border p-4 space-y-2">
        <label className="block text-sm">
          <span className="text-gray-700">상품</span>
          {loadingProducts ? (
            <Spinner />
          ) : (
            <select
              value={productId ?? ''}
              onChange={(e) => setProductId(e.target.value === '' ? null : Number(e.target.value))}
              data-testid="variant-product"
              className="mt-1 w-full rounded border px-2 py-1.5"
            >
              <option value="">상품을 고르세요</option>
              {products.map((product) => (
                <option key={product.id} value={product.id}>
                  {product.name} ({formatMoney(product.price)})
                </option>
              ))}
            </select>
          )}
        </label>
      </section>

      {productId === null ? (
        <p className="text-gray-500" data-testid="variant-no-product">
          상품을 고르면 그 상품의 옵션이 보입니다.
        </p>
      ) : (
        <>
          <section className="space-y-3">
            <h2 className="font-semibold">옵션 목록</h2>
            {loadingVariants ? (
              <Spinner />
            ) : variants.length === 0 ? (
              <p className="text-gray-500" data-testid="variant-empty">
                아직 옵션이 없습니다. 아래에서 만드세요.
              </p>
            ) : (
              <table className="w-full text-sm" data-testid="variant-table">
                <thead>
                  <tr className="border-b text-left text-gray-600">
                    <th className="py-1">SKU</th>
                    <th>옵션명</th>
                    <th>추가금</th>
                    <th>판매가</th>
                    <th>재고</th>
                    <th>상태</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {variants.map((variant) => (
                    <tr key={variant.id} className="border-b" data-testid={`variant-row-${variant.sku}`}>
                      <td className="py-1 font-mono">{variant.sku}</td>
                      <td>{variant.optionName}</td>
                      <td>{formatMoney(variant.additionalPrice)}</td>
                      {/* 할인가가 있으면 그것이 곧 판매가다 — 추가금을 겹쳐 더하면 화면 금액이
                          결제 금액과 갈린다. 계산은 api 모듈 한 곳에서 한다. */}
                      <td>
                        {selectedProduct === null
                          ? '—'
                          : formatMoney(effectivePrice(selectedProduct.price, variant))}
                      </td>
                      <td>{variant.stockQuantity}</td>
                      <td>{variant.status}</td>
                      <td className="text-right">
                        <button
                          type="button"
                          onClick={() => { setDecreaseTarget(variant); setDecreaseQty('1'); }}
                          disabled={busy}
                          data-testid={`variant-decrease-${variant.sku}`}
                          className="rounded border px-2 py-1"
                        >
                          재고 차감
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          {decreaseTarget !== null && (
            <section
              className="space-y-2 rounded border border-amber-300 bg-amber-50 p-3"
              data-testid="variant-decrease-confirm"
            >
              {/* 무엇을 얼마나 줄이는지 다시 적는다. 주문 없이 재고가 주는 조작이고 되돌릴 수 없다. */}
              <p className="text-sm">
                {decreaseTarget.sku}({decreaseTarget.optionName}) 의 재고
                {' '}{decreaseTarget.stockQuantity}개에서 줄입니다. 되돌릴 수 없습니다.
              </p>
              <label className="block text-sm">
                <span className="text-gray-700">줄일 수량</span>
                <input
                  type="number"
                  min={1}
                  step={1}
                  value={decreaseQty}
                  onChange={(e) => setDecreaseQty(e.target.value)}
                  data-testid="variant-decrease-qty"
                  className="mt-1 w-32 rounded border px-2 py-1.5"
                />
              </label>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => void decrease()}
                  disabled={busy}
                  data-testid="variant-decrease-confirm-btn"
                  className="rounded bg-red-600 px-3 py-1.5 text-white disabled:opacity-50"
                >
                  차감하기
                </button>
                <button
                  type="button"
                  onClick={() => setDecreaseTarget(null)}
                  disabled={busy}
                  className="rounded border px-3 py-1.5"
                >
                  취소
                </button>
              </div>
            </section>
          )}

          <section className="space-y-3" data-testid="variant-cost-section">
            <h2 className="font-semibold">매입가 · 마진</h2>
            <p className="text-sm text-gray-500">
              여기 숫자는 관리자만 봅니다 — 구매자 화면과 주문 경로의 응답에는 실리지 않습니다.
              마진율은 판매가 대비(매출총이익률)이며, 매입가를 넣지 않은 SKU 는 0%가 아니라
              빈칸(—)으로 남습니다. 비워서 저장하면 매입가를 지웁니다.
            </p>
            {costError !== null && (
              <p role="alert" className="text-red-600" data-testid="variant-cost-error">
                {costError}
              </p>
            )}
            {costs.length === 0 ? (
              <p className="text-gray-500" data-testid="variant-cost-empty">
                매입가를 넣을 옵션이 없습니다.
              </p>
            ) : (
              <table className="w-full text-sm" data-testid="variant-cost-table">
                <thead>
                  <tr className="border-b text-left text-gray-600">
                    <th className="py-1">SKU</th>
                    <th>판매가</th>
                    <th>매입가</th>
                    <th>마진</th>
                    <th>마진율</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {costs.map((cost) => (
                    <tr key={cost.variantId} className="border-b" data-testid={`variant-cost-row-${cost.sku}`}>
                      <td className="py-1 font-mono">{cost.sku}</td>
                      <td>{formatMoney(cost.sellingPrice)}</td>
                      <td>
                        <input
                          type="number"
                          min={0}
                          step={1}
                          placeholder="비우면 미입력"
                          value={costDrafts[cost.variantId] ?? (cost.purchasePrice ?? '')}
                          onChange={(e) =>
                            setCostDrafts((prev) => ({ ...prev, [cost.variantId]: e.target.value }))}
                          data-testid={`variant-cost-input-${cost.sku}`}
                          className="w-32 rounded border px-2 py-1"
                        />
                      </td>
                      {/* 역마진은 빨갛게 드러낸다. 0 으로 깎아 감추면 손해 보는 SKU 가 안 보인다. */}
                      <td className={cost.marginAmount != null && cost.marginAmount < 0 ? 'text-red-600' : ''}>
                        {formatMoney(cost.marginAmount)}
                      </td>
                      <td className={cost.marginRate != null && cost.marginRate < 0 ? 'text-red-600' : ''}>
                        {formatRate(cost.marginRate)}
                      </td>
                      <td className="text-right">
                        <button
                          type="button"
                          onClick={() => void saveCost(cost)}
                          disabled={busy}
                          data-testid={`variant-cost-save-${cost.sku}`}
                          className="rounded border px-2 py-1"
                        >
                          저장
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <section className="rounded border p-4 space-y-3">
            <h2 className="font-semibold">옵션 추가</h2>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block text-sm">
                <span className="text-gray-700">SKU</span>
                <span className="ml-1 text-xs text-gray-500">상품 안에서 유일해야 합니다</span>
                <input
                  type="text"
                  value={form.sku}
                  onChange={(e) => setForm({ ...form, sku: e.target.value })}
                  data-testid="variant-sku"
                  className="mt-1 w-full rounded border px-2 py-1.5"
                />
              </label>
              <label className="block text-sm">
                <span className="text-gray-700">옵션명</span>
                <input
                  type="text"
                  value={form.optionName}
                  onChange={(e) => setForm({ ...form, optionName: e.target.value })}
                  data-testid="variant-optionName"
                  className="mt-1 w-full rounded border px-2 py-1.5"
                />
              </label>
              <label className="block text-sm">
                <span className="text-gray-700">추가금</span>
                <span className="ml-1 text-xs text-gray-500">비우면 미설정</span>
                <input
                  type="number"
                  value={form.additionalPrice}
                  onChange={(e) => setForm({ ...form, additionalPrice: e.target.value })}
                  data-testid="variant-additionalPrice"
                  className="mt-1 w-full rounded border px-2 py-1.5"
                />
              </label>
              <label className="block text-sm">
                <span className="text-gray-700">초기 재고</span>
                <input
                  type="number"
                  min={0}
                  step={1}
                  value={form.initialStock}
                  onChange={(e) => setForm({ ...form, initialStock: e.target.value })}
                  data-testid="variant-initialStock"
                  className="mt-1 w-full rounded border px-2 py-1.5"
                />
              </label>
            </div>
            <button
              type="button"
              onClick={() => void create()}
              disabled={!canCreate || busy}
              data-testid="variant-create"
              className="rounded bg-blue-600 px-3 py-1.5 text-white disabled:opacity-50"
            >
              옵션 만들기
            </button>
          </section>

          <section className="rounded border p-4 space-y-3">
            <h2 className="font-semibold">옵션 조합 해석</h2>
            <p className="text-sm text-gray-500">
              고객이 고른 경로가 어떤 SKU 로 이어지는지 확인합니다. 주문이 쓰는 것과 같은 경로라
              여기서 못 찾으면 주문에서도 못 찾습니다.
            </p>
            {selections.map((selection, index) => (
              <div key={index} className="grid gap-2 sm:grid-cols-2">
                <input
                  type="text"
                  placeholder="축 이름 (예: 색상)"
                  value={selection.name}
                  onChange={(e) => updateSelection(index, { name: e.target.value })}
                  data-testid={`variant-selection-name-${index}`}
                  className="rounded border px-2 py-1.5"
                />
                <input
                  type="text"
                  placeholder="값 (예: 빨강)"
                  value={selection.value}
                  onChange={(e) => updateSelection(index, { value: e.target.value })}
                  data-testid={`variant-selection-value-${index}`}
                  className="rounded border px-2 py-1.5"
                />
              </div>
            ))}
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setSelections([...selections, { name: '', value: '' }])}
                data-testid="variant-selection-add"
                className="rounded border px-3 py-1.5"
              >
                축 추가
              </button>
              <button
                type="button"
                onClick={() => void resolve()}
                disabled={busy}
                data-testid="variant-resolve"
                className="rounded bg-blue-600 px-3 py-1.5 text-white disabled:opacity-50"
              >
                해석하기
              </button>
            </div>
            {resolveError !== null && (
              <p role="alert" className="text-amber-700" data-testid="variant-resolve-error">
                {resolveError}
              </p>
            )}
            {resolved !== null && (
              <p className="text-sm" data-testid="variant-resolved">
                {resolved.sku}({resolved.optionName}) · 재고 {resolved.stockQuantity}개
              </p>
            )}
          </section>
        </>
      )}
    </main>
  );
};

export default ProductVariantAdmin;
