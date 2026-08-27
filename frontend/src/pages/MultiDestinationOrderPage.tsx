import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useCart } from '@/contexts/useCart';
import { useAuth } from '@/contexts/useAuth';
import { orderApi, type MultiDestinationRequest } from '@/api/order';
import { isStaleTermsError } from '@/api/privacyConsent';
import { usePrivacyConsent } from '@/lib/usePrivacyConsent';
import { emptyShippingAddress, isShippingAddressComplete } from '@/lib/shippingAddress';
import { errorDetail } from '@/lib/apiError';
import ShippingAddressForm from '@/components/shipping/ShippingAddressForm';
import PrivacyConsentBlock from '@/components/consent/PrivacyConsentBlock';
import Spinner from '@/components/Spinner';
import type { MultiDestinationOrderResponse, ShippingAddressRequest } from '@/types';

/**
 * 서버 {@code CreateMultiDestinationOrderUseCase.MAX_DESTINATIONS} 의 사본.
 * 넘겨도 서버가 400 으로 거절하므로 판정의 정본은 서버다 — 여기서는 버튼을 먼저 잠글 뿐이다.
 */
const MAX_DESTINATIONS = 20;

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const newIdempotencyKey = (): string =>
  globalThis.crypto?.randomUUID?.() ??
  `multi-dest-${Date.now()}-${Math.random().toString(36).slice(2)}`;

interface Destination {
  /** 리스트 key. 배열 인덱스를 쓰면 가운데를 지웠을 때 아래 폼들의 입력 상태가 한 칸씩 밀린다. */
  key: number;
  address: ShippingAddressRequest;
  /** 상품 id → 이 배송지로 보낼 수량. 없는 키는 0 이다. */
  quantities: Record<number, number>;
}

const newDestination = (key: number): Destination => ({
  key,
  address: emptyShippingAddress(),
  quantities: {},
});

/**
 * 여러 곳 배송 — 한 번에 담고 여러 주소로 나눠 보내기.
 *
 * <p><b>왜 생겼나.</b> 그전까지 선물처럼 주소가 갈리는 주문은 방법이 하나뿐이었다: 주소마다
 * 장바구니를 다시 담아 결제를 여러 번 하는 것. 그래서 주문이 서로 남남이 되고, 한 번에 취소할
 * 수도 배송을 한 화면에서 볼 수도 없었다.
 *
 * <p><b>이 화면이 하지 않는 일 — 금액 계산.</b> 화면은 <b>수량 배분</b>만 받는다. 배송지마다
 * 그 배송지로 갈 라인만 서버로 보내고, 단가·배송비·총액은 서버가 확정한다. 옮겨 온 원본
 * (ssg-front)의 같은 기능은 반대였다: 장바구니 총액(상품값 + 배송비)을 배송지 수만큼 더해
 * 청구하면서 재고는 한 벌만 뺐다. 두 곳으로 보내면 값은 두 배인데 물건은 한 벌이었다.
 * 그래서 이 화면의 &lsquo;예상&rsquo; 금액은 어디까지나 참고값이고, 결과 화면이 보여 주는 금액은
 * 서버가 돌려준 값이다.
 *
 * <p>쿠폰 칸이 없는 것도 빠뜨린 게 아니다. 쿠폰의 최소 주문금액·1 인 한도는 주문 <b>한 건</b>에
 * 걸리는 조건이라 한 장을 N 건에 나누려면 배분 규칙이 먼저 있어야 한다. 서버도 이 경로에서는
 * 쿠폰을 받지 않는다.
 */
const MultiDestinationOrderPage: React.FC = () => {
  const { items, clearCart, loading: cartLoading } = useCart();
  const { userId, loading: authLoading } = useAuth();
  const consent = usePrivacyConsent();

  const [destinations, setDestinations] = useState<Destination[]>([
    newDestination(1),
    newDestination(2),
  ]);
  const [nextKey, setNextKey] = useState(3);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [placed, setPlaced] = useState<MultiDestinationOrderResponse | null>(null);

  /** 상품 id → 아직 어느 배송지에도 배정하지 않은 수량. */
  const unassigned = useMemo(() => {
    const rest: Record<number, number> = {};
    items.forEach(({ product, quantity }) => {
      const assigned = destinations.reduce((sum, d) => sum + (d.quantities[product.id] ?? 0), 0);
      rest[product.id] = quantity - assigned;
    });
    return rest;
  }, [items, destinations]);

  const totalUnassigned = Object.values(unassigned).reduce((a, b) => a + b, 0);

  const setQuantity = (destKey: number, productId: number, raw: number) => {
    setDestinations((prev) => prev.map((d) => {
      if (d.key !== destKey) return d;
      const current = d.quantities[productId] ?? 0;
      // 남은 수량을 넘겨 적을 수 없다 — 장바구니에 없는 것을 배정하면 서버가 재고에서 걸러 주기
      // 전에 화면이 먼저 거짓말을 하게 된다.
      const cap = current + (unassigned[productId] ?? 0);
      const next = Math.max(0, Math.min(Number.isFinite(raw) ? Math.trunc(raw) : 0, cap));
      return { ...d, quantities: { ...d.quantities, [productId]: next } };
    }));
  };

  const setAddress = (destKey: number, address: ShippingAddressRequest) => {
    setDestinations((prev) => prev.map((d) => (d.key === destKey ? { ...d, address } : d)));
  };

  const addDestination = () => {
    if (destinations.length >= MAX_DESTINATIONS) return;
    setDestinations((prev) => [...prev, newDestination(nextKey)]);
    setNextKey((k) => k + 1);
  };

  const removeDestination = (destKey: number) => {
    // 둘 아래로는 못 내려간다 — 배송지가 하나면 이 화면이 아니라 평범한 결제다(서버도 400 이다).
    if (destinations.length <= 2) return;
    setDestinations((prev) => prev.filter((d) => d.key !== destKey));
  };

  const linesOf = (d: Destination) =>
    items
      .map(({ product }) => ({ productId: product.id, quantity: d.quantities[product.id] ?? 0 }))
      .filter((l) => l.quantity > 0);

  const estimateOf = (d: Destination) =>
    items.reduce((sum, { product }) => sum + product.price * (d.quantities[product.id] ?? 0), 0);

  const everyAddressReady = destinations.every((d) => isShippingAddressComplete(d.address));
  const everyDestinationHasLines = destinations.every((d) => linesOf(d).length > 0);
  const canOrder =
    !submitting
    && userId !== null
    && items.length > 0
    && totalUnassigned === 0
    && everyAddressReady
    && everyDestinationHasLines
    && consent.ready;

  /** 버튼이 잠긴 이유. 안 적으면 사용자는 화면이 고장난 줄 안다. */
  const blockedReason = (): string | null => {
    if (userId === null) return '로그인이 필요합니다.';
    if (items.length === 0) return null;
    if (!everyDestinationHasLines) return '상품을 하나도 배정하지 않은 배송지가 있습니다.';
    if (totalUnassigned > 0) return `아직 배정하지 않은 상품이 ${totalUnassigned}개 있습니다.`;
    if (!everyAddressReady) return '배송지 정보를 모두 채워주세요.';
    if (!consent.ready) return '필수 동의 항목을 확인해주세요.';
    return null;
  };

  const submit = async () => {
    if (userId === null) return;
    setSubmitting(true);
    setError(null);
    try {
      const payload: MultiDestinationRequest[] = destinations.map((d) => ({
        shippingAddress: d.address,
        lines: linesOf(d),
      }));
      const result = await orderApi.createMultiDestinationOrder(
        userId, payload, consent.acceptances, newIdempotencyKey());
      setPlaced(result);
      clearCart();
    } catch (err) {
      // 409 는 "동의 문안이 바뀌었다" 는 뜻이다. 완료 화면으로 보내면 사용자가 할 수 있는 일이
      // 아무것도 없는 자리에 갇힌다 — 되돌아가 바뀐 문장을 다시 확인하게 한다.
      if (isStaleTermsError(err)) {
        await consent.reload();
        setError('동의 문안이 변경되었습니다. 바뀐 내용을 확인하고 다시 동의해주세요.');
        return;
      }
      setError(errorDetail(err, '주문을 만들지 못했습니다.'));
    } finally {
      setSubmitting(false);
    }
  };

  if (cartLoading || authLoading) return <Spinner size="md" message="불러오는 중..." />;

  /* ── 완료 ── */
  if (placed) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-8 space-y-6">
        <header>
          <h1 className="text-2xl font-bold text-gray-900">주문이 접수되었습니다</h1>
          <p className="mt-1 text-sm text-gray-500">
            배송지 {placed.orders.length}곳으로 나눠 보냅니다. 묶음 번호 {placed.destinationGroupId}
          </p>
        </header>

        <section className="bg-white rounded-xl border border-gray-200 p-5 space-y-3">
          {/* 서버가 확정한 합계를 그대로 쓴다 — 화면이 다시 더하면 갈라질 자리가 하나 더 생긴다. */}
          <p className="text-sm text-gray-700">
            총 결제 금액 <b className="text-gray-900">{fmt(placed.totalAmount)}</b>
          </p>
          <ul className="divide-y divide-gray-100">
            {placed.orders.map((o) => (
              <li key={o.id} className="py-2.5 text-sm">
                <p className="font-medium text-gray-900">
                  주문 #{o.id} · {fmt(o.amount)}
                </p>
                <p className="text-xs text-gray-500 mt-0.5">
                  {o.shippingAddress
                    ? `${o.shippingAddress.recipientName} · (${o.shippingAddress.postalCode}) ${o.shippingAddress.address1}`
                    : '배송지 정보 없음'}
                </p>
              </li>
            ))}
          </ul>
        </section>

        <Link to="/mypage" className="inline-block text-sm font-semibold text-blue-600 hover:text-blue-700">
          내 주문 보기 →
        </Link>
      </div>
    );
  }

  /* ── 장바구니가 비었을 때 ── */
  if (items.length === 0) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-8 space-y-4">
        <h1 className="text-2xl font-bold text-gray-900">여러 곳 배송</h1>
        <p className="text-sm text-gray-500">
          장바구니에 담은 것을 여러 주소로 나눠 보내는 화면입니다. 먼저 상품을 담아주세요.
        </p>
        <Link to="/order" className="inline-block text-sm font-semibold text-blue-600 hover:text-blue-700">
          상품 담으러 가기 →
        </Link>
      </div>
    );
  }

  const reason = blockedReason();

  return (
    <div className="max-w-4xl mx-auto px-4 py-8 space-y-6">
      <header>
        <h1 className="text-2xl font-bold text-gray-900">여러 곳 배송</h1>
        <p className="mt-1 text-sm text-gray-500">
          장바구니에 담은 수량을 배송지별로 나눠 적으면, <b>배송지마다 주문이 하나씩</b> 만들어집니다.
          금액과 배송비는 각 주문이 자기 상품으로 계산합니다.
        </p>
      </header>

      {/* 배정 현황 — 무엇이 몇 개 남았는지가 이 화면의 진행 표시다 */}
      <section className="bg-white rounded-xl border border-gray-200 p-5">
        <h2 className="text-sm font-semibold text-gray-900 mb-3">배정할 상품</h2>
        <ul className="divide-y divide-gray-100">
          {items.map(({ product, quantity }) => (
            <li key={product.id} className="py-2 flex items-center justify-between gap-3 text-sm">
              <span className="text-gray-800 truncate">{product.name}</span>
              <span className={unassigned[product.id] > 0 ? 'text-amber-700' : 'text-gray-400'}>
                {quantity - (unassigned[product.id] ?? 0)} / {quantity}개 배정
              </span>
            </li>
          ))}
        </ul>
        {totalUnassigned > 0 && (
          <p className="mt-3 text-xs text-amber-700">
            아직 {totalUnassigned}개가 어느 배송지에도 배정되지 않았습니다.
          </p>
        )}
      </section>

      {/* 배송지들 */}
      {destinations.map((d, index) => (
        <section
          key={d.key}
          aria-label={`배송지 ${index + 1}`}
          className="bg-white rounded-xl border border-gray-200 p-5 space-y-4"
        >
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-sm font-semibold text-gray-900">배송지 {index + 1}</h2>
            {destinations.length > 2 && (
              <button
                type="button"
                onClick={() => removeDestination(d.key)}
                disabled={submitting}
                className="text-xs text-red-600 hover:text-red-700 disabled:opacity-40"
              >
                이 배송지 빼기
              </button>
            )}
          </div>

          <ShippingAddressForm
            value={d.address}
            onChange={(next) => setAddress(d.key, next)}
            disabled={submitting}
            idPrefix={`dest-${d.key}`}
          />

          <div className="space-y-2">
            <h3 className="font-bold text-gray-900">보낼 상품</h3>
            {items.map(({ product }) => {
              const value = d.quantities[product.id] ?? 0;
              const inputId = `dest-${d.key}-qty-${product.id}`;
              return (
                <div key={product.id} className="flex items-center justify-between gap-3">
                  <label htmlFor={inputId} className="text-sm text-gray-700 truncate">
                    {product.name}
                  </label>
                  <input
                    id={inputId}
                    type="number"
                    min={0}
                    max={value + (unassigned[product.id] ?? 0)}
                    value={value}
                    disabled={submitting}
                    onChange={(e) => setQuantity(d.key, product.id, Number(e.target.value))}
                    className="w-20 px-2 py-1.5 border border-gray-300 rounded-lg text-sm text-right disabled:bg-gray-100"
                  />
                </div>
              );
            })}
            <p className="text-xs text-gray-400 pt-1">
              예상 상품 금액 {fmt(estimateOf(d))} <span className="text-gray-300">(배송비 별도 · 서버가 확정)</span>
            </p>
          </div>
        </section>
      ))}

      <button
        type="button"
        onClick={addDestination}
        disabled={submitting || destinations.length >= MAX_DESTINATIONS}
        className="px-4 py-2 text-sm font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-40"
      >
        배송지 추가
      </button>
      {destinations.length >= MAX_DESTINATIONS && (
        <p className="text-xs text-gray-400">한 번에 {MAX_DESTINATIONS}곳까지 보낼 수 있습니다.</p>
      )}

      <section className="bg-white rounded-xl border border-gray-200 p-5">
        <PrivacyConsentBlock
          terms={consent.terms}
          agreed={consent.agreed}
          onToggle={consent.toggle}
          loading={consent.loading}
          error={consent.error}
          disabled={submitting}
        />
      </section>

      {error && (
        <div className="rounded-lg bg-red-50 border border-red-200 p-3 text-sm text-red-800">{error}</div>
      )}

      <div className="space-y-2">
        <button
          type="button"
          onClick={() => void submit()}
          disabled={!canOrder}
          className="w-full px-4 py-3 rounded-lg bg-blue-600 text-white text-sm font-semibold hover:bg-blue-700 disabled:opacity-40"
        >
          {submitting ? '주문을 만드는 중...' : `${destinations.length}곳으로 주문하기`}
        </button>
        {reason && <p className="text-xs text-gray-500 text-center">{reason}</p>}
      </div>
    </div>
  );
};

export default MultiDestinationOrderPage;
