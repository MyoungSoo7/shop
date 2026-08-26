import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useCart, CartItem } from '@/contexts/useCart';
import { orderApi } from '@/api/order';
import { paymentApi } from '@/api/payment';
import { CouponPreviewResponse, MultiItemOrderResponse, ShippingAddressRequest } from '@/types';
import Spinner from '@/components/Spinner';
import CouponInput from '@/components/coupon/CouponInput';
import ShippingAddressForm from '@/components/shipping/ShippingAddressForm';
import PrivacyConsentBlock from '@/components/consent/PrivacyConsentBlock';
import { emptyShippingAddress, isShippingAddressComplete } from '@/lib/shippingAddress';
import { usePrivacyConsent } from '@/lib/usePrivacyConsent';
import { isStaleTermsError } from '@/api/privacyConsent';
import { errorDetail } from '@/lib/apiError';

const USER_ID = 1;
const TOSS_CLIENT_KEY = import.meta.env.VITE_TOSS_CLIENT_KEY as string;

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const loadTossScript = (): Promise<void> =>
  new Promise((resolve, reject) => {
    if (window.TossPayments) { resolve(); return; }
    const script = document.createElement('script');
    script.src = 'https://js.tosspayments.com/v1/payment';
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('토스 스크립트 로드 실패'));
    document.head.appendChild(script);
  });

/* ─────────────────────────────────────────
   장바구니 아이템 행
───────────────────────────────────────── */
interface CartItemRowProps {
  item: CartItem;
  onRemove: () => void;
  onQuantityChange: (q: number) => void;
  disabled: boolean;
}

const CartItemRow: React.FC<CartItemRowProps> = ({ item, onRemove, onQuantityChange, disabled }) => {
  const { product, quantity } = item;
  return (
    <div className="flex items-center gap-4 py-4 border-b border-gray-100 last:border-0">
      {product.primaryImageUrl ? (
        <img src={product.primaryImageUrl} alt={product.name}
          className="w-16 h-16 rounded-lg object-cover flex-shrink-0" />
      ) : (
        <div className="w-16 h-16 rounded-lg bg-gray-100 flex items-center justify-center flex-shrink-0">
          <svg className="w-7 h-7 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
              d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
          </svg>
        </div>
      )}

      <div className="flex-1 min-w-0">
        <p className="font-semibold text-gray-900 text-sm truncate">{product.name}</p>
        <p className="text-sm text-blue-600 font-medium mt-0.5">{fmt(product.price)}</p>
        {product.description && (
          <p className="text-xs text-gray-400 truncate mt-0.5">{product.description}</p>
        )}
      </div>

      <div className="flex items-center gap-1 flex-shrink-0">
        <button onClick={() => onQuantityChange(quantity - 1)} disabled={disabled}
          className="w-7 h-7 flex items-center justify-center rounded border border-gray-300 text-gray-600 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed">
          <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M20 12H4" />
          </svg>
        </button>
        <span className="w-8 text-center text-sm font-semibold text-gray-900">{quantity}</span>
        <button onClick={() => onQuantityChange(quantity + 1)}
          disabled={disabled || quantity >= product.stockQuantity}
          className="w-7 h-7 flex items-center justify-center rounded border border-gray-300 text-gray-600 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed">
          <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 4v16m8-8H4" />
          </svg>
        </button>
      </div>

      <div className="text-right flex-shrink-0 w-24">
        <p className="font-bold text-gray-900 text-sm">{fmt(product.price * quantity)}</p>
        <p className="text-xs text-gray-400 mt-0.5">{quantity}개</p>
      </div>

      <button onClick={onRemove} disabled={disabled}
        className="text-gray-400 hover:text-red-500 transition-colors disabled:opacity-40 disabled:cursor-not-allowed">
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
            d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
        </svg>
      </button>
    </div>
  );
};

/**
 * 멱등 키 — 같은 키의 재요청은 새 주문을 만들지 않고 기존 주문을 돌려준다.
 * 네트워크 재시도로 주문이 두 건 생기는 것을 막는다.
 */
const newIdempotencyKey = (): string =>
  globalThis.crypto?.randomUUID?.() ??
  `cart-${Date.now()}-${Math.random().toString(36).slice(2)}`;

/* ─────────────────────────────────────────
   CartPage
───────────────────────────────────────── */
const CartPage: React.FC = () => {
  const { items, removeItem, updateQuantity, clearCart, totalAmount, totalCount } = useCart();
  const [paymentMethod, setPaymentMethod] = useState('CARD');
  const [checkoutStep, setCheckoutStep] = useState<'cart' | 'processing' | 'done'>('cart');
  const [processingMsg, setProcessingMsg] = useState('');
  const [placedOrder, setPlacedOrder] = useState<MultiItemOrderResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [couponResult, setCouponResult] = useState<CouponPreviewResponse | null>(null);
  const [appliedCouponCode, setAppliedCouponCode] = useState<string | undefined>(undefined);
  const [shippingAddress, setShippingAddress] = useState<ShippingAddressRequest>(emptyShippingAddress);
  const addressReady = isShippingAddressComplete(shippingAddress);
  const consent = usePrivacyConsent();
  const canOrder = addressReady && consent.ready;

  // 화면에 보여줄 예상 금액. 확정 금액은 주문을 만든 서버 응답에서 온다.
  const discountedTotal = couponResult ? couponResult.finalAmount : totalAmount;

  /**
   * 서버로 보낼 주문 라인. 금액이 없다는 게 요점이다 — 단가·쿠폰 할인·배송비는 서버가 상품
   * 마스터에서 확정한다. 쿠폰 미리보기도 같은 라인을 쓰므로 미리보기 금액과 결제 금액이 갈라지지 않는다.
   */
  const orderLines = useMemo(
    () => items.map(({ product, quantity }) => ({ productId: product.id, quantity })),
    [items],
  );

  /* ── 일반 결제 (CARD / BANK_TRANSFER / VIRTUAL_ACCOUNT) ── */
  const handleNormalCheckout = async () => {
    setCheckoutStep('processing');
    setError(null);
    setProcessingMsg('주문을 만드는 중...');

    try {
      // 장바구니 전체가 주문 1건. 쿠폰 사용 기록·재고 차감도 서버가 같은 트랜잭션에서 하므로
      // 여기서 couponApi.use 를 부르면 안 된다(두 번 소진된다).
      const created = await orderApi.createMultiItemOrder(
        USER_ID, orderLines, shippingAddress, consent.acceptances,
        appliedCouponCode ?? null, newIdempotencyKey());

      setProcessingMsg('결제 승인 중...');
      const payment = await paymentApi.createPayment({ orderId: created.id, paymentMethod });
      const authorized = await paymentApi.authorizePayment(payment.id);
      await paymentApi.capturePayment(authorized.id);

      setPlacedOrder(created);
      clearCart();
      setCheckoutStep('done');
    } catch (err) {
      // 409 는 "문안이 바뀌었다"는 뜻이라 되돌아가 다시 동의를 받아야 한다. 완료 화면으로
      // 보내면 사용자가 할 수 있는 일이 아무것도 없는 자리에 갇힌다.
      if (isStaleTermsError(err)) {
        await consent.reload();
        setError('동의 문안이 변경되었습니다. 바뀐 내용을 확인하고 다시 동의해주세요.');
        setCheckoutStep('cart');
        return;
      }
      setError(errorDetail(err, '알 수 없는 오류'));
      setCheckoutStep('done');
    }
  };

  /* ── 토스페이먼츠 결제 ── */
  const handleTossCheckout = async () => {
    setCheckoutStep('processing');
    setProcessingMsg('주문을 생성하는 중...');
    setError(null);

    // 1. 주문 선생성 (CREATED 상태) — 장바구니 전체가 한 건이다.
    let created: MultiItemOrderResponse;
    try {
      created = await orderApi.createMultiItemOrder(
        USER_ID, orderLines, shippingAddress, consent.acceptances,
        appliedCouponCode ?? null, newIdempotencyKey());
    } catch (err) {
      if (isStaleTermsError(err)) {
        await consent.reload();
        setError('동의 문안이 변경되었습니다. 바뀐 내용을 확인하고 다시 동의해주세요.');
        setCheckoutStep('cart');
        return;
      }
      setError(`주문 생성 실패: ${errorDetail(err, '알 수 없는 오류')}`);
      setCheckoutStep('cart');
      return;
    }

    // 2. Toss SDK 로드 후 결제 요청
    setProcessingMsg('토스페이먼츠 결제창 여는 중...');
    try {
      await loadTossScript();
      const tossPayments = window.TossPayments?.(TOSS_CLIENT_KEY);
      if (!tossPayments) throw new Error('토스페이먼츠 스크립트를 불러오지 못했습니다.');
      const tossOrderId = `CART-${Date.now()}`;
      const firstName = items[0].product.name;
      const orderName = items.length > 1
        ? `${firstName} 외 ${items.length - 1}개`
        : firstName;

      const successUrl =
        `${window.location.origin}/order/toss/success` +
        `?type=cart&dbOrderIds=${created.id}`;

      await tossPayments.requestPayment('카드', {
        // 화면에서 계산한 값이 아니라 서버가 주문에 못박은 금액. 결제 승인 때 서버가
        // 주문 금액과 결제 금액이 같은지 다시 검사하므로, 여기서 다른 값을 넣으면 승인이 거절된다.
        amount: created.amount,
        orderId: tossOrderId,
        orderName,
        customerName: '테스트 고객',
        successUrl,
        failUrl: `${window.location.origin}/order/toss/fail`,
      });
      // 리다이렉트 발생 — 이후 코드 실행 안 됨
    } catch (err) {
      setError(errorDetail(err, '토스페이먼츠 결제창을 열 수 없습니다.'));
      setCheckoutStep('cart');
    }
  };

  const handleCheckout = () => {
    if (paymentMethod === 'TOSS_PAYMENTS') {
      handleTossCheckout();
    } else {
      handleNormalCheckout();
    }
  };

  /* ── 빈 장바구니 ── */
  if (items.length === 0 && checkoutStep === 'cart') {
    return (
      <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-50 py-10 px-4">
        <div className="max-w-2xl mx-auto">
          <h1 className="text-3xl font-bold text-gray-900 text-center mb-8">장바구니</h1>
          <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-16 text-center">
            <svg className="mx-auto h-16 w-16 text-gray-300 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
                d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z" />
            </svg>
            <p className="text-gray-500 text-lg mb-6">장바구니가 비어있습니다.</p>
            <Link to="/order"
              className="inline-block bg-blue-600 text-white px-8 py-3 rounded-xl font-semibold hover:bg-blue-700 transition-colors">
              상품 보러 가기
            </Link>
          </div>
        </div>
      </div>
    );
  }

  /* ── 처리 중 ── */
  if (checkoutStep === 'processing') {
    return (
      <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-50 py-10 px-4">
        <div className="max-w-2xl mx-auto">
          <h1 className="text-3xl font-bold text-gray-900 text-center mb-8">장바구니</h1>
          <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-12 text-center">
            <Spinner size="lg" message={processingMsg} />
            {paymentMethod !== 'TOSS_PAYMENTS' && (
              <p className="text-sm text-gray-400 mt-4">
                {totalCount}개 상품을 한 건으로 주문합니다.
              </p>
            )}
            {paymentMethod === 'TOSS_PAYMENTS' && (
              <p className="text-sm text-gray-400 mt-4">
                잠시 후 토스페이먼츠 결제 화면으로 이동합니다.
              </p>
            )}
          </div>
        </div>
      </div>
    );
  }

  /* ── 주문 완료 ── */
  if (checkoutStep === 'done') {
    return (
      <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-50 py-10 px-4">
        <div className="max-w-2xl mx-auto">
          <h1 className="text-3xl font-bold text-gray-900 text-center mb-8">주문 결과</h1>
          <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-8">

            <div className="text-center mb-6">
              {placedOrder && !error ? (
                <>
                  <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-green-100 mb-4">
                    <svg className="h-9 w-9 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
                    </svg>
                  </div>
                  <h2 className="text-xl font-bold text-gray-900">주문 완료!</h2>
                  <p className="text-sm text-gray-500 mt-1">
                    주문 #{placedOrder.id} · {placedOrder.items.length}개 상품
                  </p>
                </>
              ) : (
                <>
                  <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-yellow-100 mb-4">
                    <svg className="h-9 w-9 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                        d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
                    </svg>
                  </div>
                  <h2 className="text-xl font-bold text-gray-900">주문 실패</h2>
                  <p className="text-sm text-red-600 mt-1">{error}</p>
                </>
              )}
            </div>

            {placedOrder && (
              <div className="bg-gray-50 rounded-xl p-4 mb-6 space-y-2">
                {placedOrder.items.map((line) => (
                  <div key={line.id} className="flex justify-between items-center text-sm">
                    <div className="flex items-center gap-2">
                      <svg className="w-4 h-4 text-green-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
                      </svg>
                      <span className="text-gray-700 truncate max-w-[200px]">{line.productName}</span>
                      <span className="text-gray-400 text-xs">× {line.quantity}</span>
                    </div>
                    <span className="font-semibold text-gray-900 flex-shrink-0">{fmt(line.lineAmount)}</span>
                  </div>
                ))}
                {/* 금액 구성은 전부 서버가 확정한 값이다. 화면에서 다시 계산하지 않는다. */}
                <div className="flex justify-between items-center pt-2 border-t border-gray-200 text-sm">
                  <span className="text-gray-500">상품 합계</span>
                  <span className="text-gray-700">{fmt(placedOrder.subtotal)}</span>
                </div>
                {placedOrder.discountAmount > 0 && (
                  <div className="flex justify-between items-center text-sm">
                    <span className="text-green-600 font-medium">쿠폰 할인</span>
                    <span className="text-green-600 font-medium">-{fmt(placedOrder.discountAmount)}</span>
                  </div>
                )}
                <div className="flex justify-between items-center text-sm">
                  <span className="text-gray-500">배송비</span>
                  <span className="text-gray-700">
                    {placedOrder.shippingFee > 0 ? fmt(placedOrder.shippingFee) : '무료'}
                  </span>
                </div>
                <div className="flex justify-between items-center pt-2 border-t border-gray-200 font-bold">
                  <span>결제 금액</span>
                  <span className="text-blue-600">{fmt(placedOrder.amount)}</span>
                </div>
              </div>
            )}

            <div className="flex gap-3">
              <Link to="/mypage"
                className="flex-1 text-center py-3 bg-gray-100 text-gray-700 rounded-xl font-semibold hover:bg-gray-200 transition-colors">
                주문 내역 보기
              </Link>
              <Link to="/order"
                className="flex-1 text-center py-3 bg-blue-600 text-white rounded-xl font-semibold hover:bg-blue-700 transition-colors">
                계속 쇼핑하기
              </Link>
            </div>
          </div>
        </div>
      </div>
    );
  }

  /* ── 장바구니 메인 ── */
  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-50 py-10 px-4">
      <div className="max-w-3xl mx-auto">

        <div className="flex items-center justify-between mb-6">
          <h1 className="text-3xl font-bold text-gray-900">장바구니</h1>
          <button onClick={clearCart}
            className="text-sm text-gray-400 hover:text-red-500 transition-colors">
            전체 삭제
          </button>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

          {/* 아이템 목록 */}
          <div className="lg:col-span-2 bg-white rounded-2xl shadow-sm border border-gray-200 p-6">
            <p className="text-sm font-medium text-gray-500 mb-3">총 {totalCount}개 상품</p>
            {items.map((item) => (
              <CartItemRow
                key={item.product.id}
                item={item}
                onRemove={() => removeItem(item.product.id)}
                onQuantityChange={(q) => updateQuantity(item.product.id, q)}
                disabled={false}
              />
            ))}
          </div>

          {/* 주문 요약 */}
          <div className="lg:col-span-1 space-y-4">
            <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-6">
              <ShippingAddressForm value={shippingAddress} onChange={setShippingAddress} />
            </div>

            <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-6">
              <PrivacyConsentBlock
                terms={consent.terms}
                agreed={consent.agreed}
                onToggle={consent.toggle}
                loading={consent.loading}
                error={consent.error}
              />
            </div>

            <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-6">
              <h2 className="font-bold text-gray-900 mb-4">주문 요약</h2>

              {/* 소계 */}
              <div className="space-y-2 mb-4">
                {items.map((item) => (
                  <div key={item.product.id} className="flex justify-between text-sm">
                    <span className="text-gray-500 truncate max-w-[140px]">
                      {item.product.name} × {item.quantity}
                    </span>
                    <span className="text-gray-700 flex-shrink-0">
                      {fmt(item.product.price * item.quantity)}
                    </span>
                  </div>
                ))}
              </div>

              {/* 쿠폰 */}
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1.5">쿠폰 코드</label>
                <CouponInput
                  userId={USER_ID}
                  lines={orderLines}
                  onApply={(result, code) => { setCouponResult(result); setAppliedCouponCode(code); }}
                  onRemove={() => { setCouponResult(null); setAppliedCouponCode(undefined); }}
                  appliedCode={appliedCouponCode}
                />
              </div>

              {/* 합계 */}
              <div className="pt-3 border-t border-gray-200 mb-5 space-y-1">
                {couponResult && (
                  <>
                    <div className="flex justify-between text-sm">
                      <span className="text-gray-500">상품 합계</span>
                      <span className="text-gray-400 line-through">{fmt(totalAmount)}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-green-600 font-medium">쿠폰 할인 ({appliedCouponCode})</span>
                      <span className="text-green-600 font-medium">-{fmt(couponResult.discountAmount)}</span>
                    </div>
                  </>
                )}
                <div className="flex justify-between items-center">
                  <span className="font-bold text-gray-900">총 결제 금액</span>
                  <span className="text-xl font-bold text-blue-600">{fmt(discountedTotal)}</span>
                </div>
              </div>

              {/* 결제 수단 */}
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1.5">결제 수단</label>
                <select
                  value={paymentMethod}
                  onChange={(e) => setPaymentMethod(e.target.value)}
                  className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                >
                  <option value="CARD">신용카드</option>
                  <option value="BANK_TRANSFER">계좌이체</option>
                  <option value="VIRTUAL_ACCOUNT">가상계좌</option>
                  <option value="TOSS_PAYMENTS">토스페이먼츠</option>
                </select>
              </div>

              {/* 토스 안내 */}
              {paymentMethod === 'TOSS_PAYMENTS' && (
                <div className="mb-4 flex items-start gap-2 bg-sky-50 border border-sky-200 rounded-lg p-3">
                  <svg className="w-4 h-4 text-sky-500 flex-shrink-0 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd"
                      d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
                      clipRule="evenodd" />
                  </svg>
                  <p className="text-xs text-sky-800">
                    장바구니 전체 금액({fmt(discountedTotal)})을 토스페이먼츠로 한 번에 결제합니다.
                    주문 생성 후 결제 화면으로 이동합니다.
                  </p>
                </div>
              )}

              {/* 에러 */}
              {error && (
                <div className="mb-4 bg-red-50 border border-red-200 rounded-lg p-3">
                  <p className="text-red-800 text-xs">{error}</p>
                </div>
              )}

              {/* 배송지 미입력 안내 — 서버도 400 으로 거절하지만 눌러보고 알게 하지는 않는다 */}
              {!addressReady && (
                <p className="mb-3 text-xs text-gray-500">
                  받는 분·연락처·우편번호·주소를 입력해야 주문할 수 있습니다.
                </p>
              )}

              {addressReady && !consent.ready && (
                <p className="mb-3 text-xs text-gray-500">
                  필수 개인정보 동의 항목에 동의해야 주문할 수 있습니다.
                </p>
              )}

              {/* 주문 버튼 */}
              <button
                onClick={handleCheckout}
                disabled={!canOrder}
                className={`w-full py-3 rounded-xl font-semibold transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${
                  paymentMethod === 'TOSS_PAYMENTS'
                    ? 'bg-sky-500 text-white hover:bg-sky-600'
                    : 'bg-blue-600 text-white hover:bg-blue-700'
                }`}
              >
                {paymentMethod === 'TOSS_PAYMENTS'
                  ? `토스페이먼츠로 ${fmt(discountedTotal)} 결제`
                  : `${items.length}개 상품 전체 주문하기`}
              </button>
            </div>

            <Link to="/order"
              className="block text-center py-3 bg-white text-gray-600 rounded-xl border border-gray-200 font-medium hover:bg-gray-50 transition-colors text-sm">
              ← 쇼핑 계속하기
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
};

export default CartPage;