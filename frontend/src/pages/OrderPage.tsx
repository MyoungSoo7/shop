import React, { useState, useEffect } from 'react';
import OptionFacetPanel from '@/components/product/OptionFacetPanel';
import {
  facetApi, countSelected, toggleFacetValue,
  type Facet, type FacetSelection,
} from '@/api/facet';
import { orderApi } from '@/api/order';
import { paymentApi } from '@/api/payment';
import { productApi } from '@/api/product';
import { reviewApi } from '@/api/review';
import { couponApi } from '@/api/coupon';
import { OrderResponse, PaymentResponse, ProductResponse, ReviewResponse, CouponValidateResponse } from '@/types';
import { useCart } from '@/contexts/useCart';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';
import StarRating from '@/components/review/StarRating';
import ReviewList from '@/components/review/ReviewList';
import CouponInput from '@/components/coupon/CouponInput';
import { apiErrorMessage, errorDetail } from '@/lib/apiError';

const PRODUCTS_PER_PAGE = 5;
const TOSS_CLIENT_KEY = import.meta.env.VITE_TOSS_CLIENT_KEY as string;

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
   주문하기 탭 - 주문 폼
───────────────────────────────────────── */
const OrderFormTab: React.FC = () => {
  const userId = 1;
  const { addItem } = useCart();
  const [addedProductId, setAddedProductId] = useState<number | null>(null);

  const [searchQuery, setSearchQuery] = useState('');
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [selectedProduct, setSelectedProduct] = useState<ProductResponse | null>(null);

  const [paymentMethod, setPaymentMethod] = useState('CARD');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [payment, setPayment] = useState<PaymentResponse | null>(null);
  const [step, setStep] = useState<'input' | 'order-created' | 'payment-ready' | 'completed'>('input');

  // 쿠폰
  const [couponResult, setCouponResult] = useState<CouponValidateResponse | null>(null);
  const [appliedCouponCode, setAppliedCouponCode] = useState<string | undefined>(undefined);

  // 옵션 파셋 필터
  const [facets, setFacets] = useState<Facet[]>([]);
  const [facetSelection, setFacetSelection] = useState<FacetSelection>({});
  const [facetProducts, setFacetProducts] = useState<ProductResponse[] | null>(null);
  const [loadingFacets, setLoadingFacets] = useState(false);

  // 상품 리뷰 미리보기
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [reviewsOpen, setReviewsOpen] = useState(false);
  const [loadingReviews, setLoadingReviews] = useState(false);

  useEffect(() => {
    setLoadingProducts(true);
    productApi.getAvailableProducts()
      .then(setProducts)
      .catch(() => setError('상품 목록을 불러오지 못했습니다.'))
      .finally(() => setLoadingProducts(false));
  }, []);

  // 파셋은 선택이 바뀔 때마다 다시 묻는다 — 값별 남는 상품 수가 선택에 따라 달라지기 때문이다.
  useEffect(() => {
    let cancelled = false;
    setLoadingFacets(true);
    facetApi.search(facetSelection)
      .then((result) => {
        if (cancelled) return;
        setFacets(result.facets);
        // 선택이 없으면 목록은 기존 전체 조회를 그대로 쓴다 — 파셋 질의는 옵션이 있는 상품만
        // 훑으므로, 선택도 없는데 이 결과로 갈아치우면 옵션 없는 상품이 화면에서 사라진다.
        setFacetProducts(countSelected(facetSelection) > 0 ? result.products : null);
      })
      .catch(() => { if (!cancelled) { setFacets([]); setFacetProducts(null); } })
      .finally(() => { if (!cancelled) setLoadingFacets(false); });
    return () => { cancelled = true; };
  }, [facetSelection]);

  useEffect(() => {
    if (!selectedProduct) { setReviews([]); setReviewsOpen(false); return; }
    setLoadingReviews(true);
    reviewApi.getProductReviews(selectedProduct.id)
      .then(setReviews)
      .catch(() => {})
      .finally(() => setLoadingReviews(false));
  }, [selectedProduct]);

  const handleToggleFacet = (axisCode: string, valueCode: string) => {
    setFacetSelection((prev) => toggleFacetValue(prev, axisCode, valueCode));
    setSelectedProduct(null); // 필터가 바뀌면 고른 상품이 목록에서 빠질 수 있다
  };

  const handleClearFacets = () => {
    setFacetSelection({});
    setSelectedProduct(null);
  };

  // 옵션 필터가 걸리면 그 결과가 목록의 원천이고, 키워드는 그 위에 얹는다.
  const filteredProducts = (facetProducts ?? products)
    .filter((p) => p.name.toLowerCase().includes(searchQuery.toLowerCase()))
    .slice(0, PRODUCTS_PER_PAGE);

  const handleCreateOrder = async () => {
    if (!selectedProduct) { setError('상품을 선택해주세요.'); return; }
    setLoading(true);
    setError(null);
    try {
      const finalAmount = couponResult ? couponResult.finalAmount : selectedProduct.price;
      const orderRes = await orderApi.createOrder({
        userId,
        productId: selectedProduct.id,
        amount: finalAmount,
      });
      setOrder(orderRes);

      // 쿠폰 사용 처리
      if (appliedCouponCode) {
        try {
          await couponApi.use(appliedCouponCode, userId, orderRes.id);
        } catch {
          // 쿠폰 사용 실패해도 주문은 유지
        }
      }

      if (paymentMethod === 'TOSS_PAYMENTS') {
        await handleTossPayment(orderRes);
      } else {
        setStep('order-created');
        setLoading(false);
      }
    } catch (err) {
      setError(apiErrorMessage(err, '주문 생성에 실패했습니다.'));
      setLoading(false);
    }
  };

  const handleTossPayment = async (orderRes: OrderResponse) => {
    try {
      await loadTossScript();
      const tossPayments = window.TossPayments?.(TOSS_CLIENT_KEY);
      if (!tossPayments) throw new Error('토스페이먼츠 스크립트를 불러오지 못했습니다.');
      const tossOrderId = `ORDER-${orderRes.id}-${Date.now()}`;

      await tossPayments.requestPayment('카드', {
        amount: Math.round(orderRes.amount),
        orderId: tossOrderId,
        orderName: selectedProduct!.name,
        customerName: '테스트 고객',
        successUrl: `${window.location.origin}/order/toss/success?dbOrderId=${orderRes.id}`,
        failUrl: `${window.location.origin}/order/toss/fail`,
      });
      // 페이지가 리다이렉트되므로 이 이후 코드는 실행되지 않음
    } catch (err) {
      setError(errorDetail(err, '토스페이먼츠 결제창을 열 수 없습니다.'));
      setLoading(false);
    }
  };

  const handleCreatePayment = async () => {
    if (!order) return;
    setLoading(true);
    setError(null);
    try {
      const paymentRes = await paymentApi.createPayment({ orderId: order.id, paymentMethod });
      setPayment(paymentRes);
      setStep('payment-ready');
    } catch (err) {
      setError(apiErrorMessage(err, '결제 생성에 실패했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  const handleAuthorizePayment = async () => {
    if (!payment) return;
    setLoading(true);
    setError(null);
    try {
      const authorized = await paymentApi.authorizePayment(payment.id);
      setPayment(authorized);
      setTimeout(() => handleCapturePayment(authorized.id), 1000);
    } catch (err) {
      setError(apiErrorMessage(err, '결제 승인에 실패했습니다.'));
      setLoading(false);
    }
  };

  const handleCapturePayment = async (paymentId: number) => {
    try {
      const captured = await paymentApi.capturePayment(paymentId);
      setPayment(captured);
      setStep('completed');
    } catch (err) {
      setError(apiErrorMessage(err, '결제 확정에 실패했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  const handleAddToCart = (product: ProductResponse) => {
    addItem(product);
    setAddedProductId(product.id);
    setTimeout(() => setAddedProductId(null), 1500);
  };

  const handleNewOrder = () => {
    setSearchQuery('');
    setSelectedProduct(null);
    setPaymentMethod('CARD');
    setOrder(null);
    setPayment(null);
    setError(null);
    setStep('input');
    setCouponResult(null);
    setAppliedCouponCode(undefined);
  };

  const fmt = (v: number) =>
    new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

  const statusBadge = (s: string) => ({
    CREATED: 'bg-yellow-100 text-yellow-800',
    PAID: 'bg-green-100 text-green-800',
    CANCELED: 'bg-red-100 text-red-800',
    READY: 'bg-blue-100 text-blue-800',
    AUTHORIZED: 'bg-indigo-100 text-indigo-800',
    CAPTURED: 'bg-green-100 text-green-800',
  }[s] ?? 'bg-gray-100 text-gray-800');

  return (
    <>
      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 rounded-lg p-3">
          <p className="text-red-800 text-sm">{error}</p>
        </div>
      )}

      {/* Step 1: 주문 정보 입력 */}
      {step === 'input' && (
        <Card title="상품 선택 및 결제">
          <div className="space-y-5">

            {/* 상품 검색 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">상품 검색</label>
              <input
                type="text"
                className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                placeholder="상품명을 입력하세요"
                value={searchQuery}
                onChange={(e) => { setSearchQuery(e.target.value); setSelectedProduct(null); }}
              />
            </div>

            {/* 옵션 파셋 필터 — 옵션 있는 상품이 없으면 패널 자체가 그려지지 않는다 */}
            <OptionFacetPanel
              facets={facets}
              selection={facetSelection}
              onToggle={handleToggleFacet}
              onClear={handleClearFacets}
              loading={loadingFacets}
            />

            {/* 상품 목록 */}
            <div>
              {loadingProducts ? (
                <Spinner size="sm" message="상품 불러오는 중..." />
              ) : filteredProducts.length === 0 ? (
                <p className="text-sm text-gray-400 text-center py-6">
                  {searchQuery ? '검색 결과가 없습니다.' : '판매 가능한 상품이 없습니다.'}
                </p>
              ) : (
                <ul className="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden">
                  {filteredProducts.map((product) => {
                    const isSelected = selectedProduct?.id === product.id;
                    return (
                      <li
                        key={product.id}
                        onClick={() => setSelectedProduct(product)}
                        className={`flex items-center justify-between px-4 py-3 cursor-pointer transition-colors ${
                          isSelected ? 'bg-blue-50 border-l-4 border-blue-500' : 'hover:bg-gray-50'
                        }`}
                      >
                        <div className="flex items-center gap-3">
                          {product.primaryImageUrl ? (
                            <img src={product.primaryImageUrl} alt={product.name}
                              className="w-10 h-10 rounded object-cover flex-shrink-0" />
                          ) : (
                            <div className="w-10 h-10 rounded bg-gray-100 flex items-center justify-center flex-shrink-0">
                              <span className="text-gray-400 text-xs">없음</span>
                            </div>
                          )}
                          <div>
                            <p className="text-sm font-medium text-gray-900">{product.name}</p>
                            {product.description && (
                              <p className="text-xs text-gray-400 truncate max-w-xs">{product.description}</p>
                            )}
                          </div>
                        </div>
                        <div className="flex items-center gap-2 flex-shrink-0">
                          <div className="text-right">
                            <p className="text-sm font-semibold text-gray-900">{fmt(product.price)}</p>
                            <p className="text-xs text-gray-400">재고 {product.stockQuantity}개</p>
                          </div>
                          {/* `tap-target` — 아이콘 버튼이라 시각 크기는 30×30 이지만 터치 환경에서는
                              눌리는 영역만 44×44 로 넓힌다(index.css, 데스크톱 무영향). */}
                          <button
                            onClick={(e) => { e.stopPropagation(); handleAddToCart(product); }}
                            title="장바구니 담기"
                            className={`tap-target p-1.5 rounded-lg border transition-all flex-shrink-0 ${
                              addedProductId === product.id
                                ? 'bg-green-500 border-green-500 text-white'
                                : 'border-gray-300 text-gray-500 hover:border-blue-400 hover:text-blue-500 hover:bg-blue-50'
                            }`}
                          >
                            {addedProductId === product.id ? (
                              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M5 13l4 4L19 7" />
                              </svg>
                            ) : (
                              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8"
                                  d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z" />
                              </svg>
                            )}
                          </button>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
              {products.length > 0 && (
                <p className="text-xs text-gray-400 mt-1 text-right">
                  최대 {PRODUCTS_PER_PAGE}개 표시 · 전체 {products.length}개
                </p>
              )}
            </div>

            {/* 선택 상품 요약 */}
            {selectedProduct && (
              <div className="bg-blue-50 border border-blue-200 rounded-lg p-3.5">
                <p className="text-xs font-medium text-blue-600 mb-1">선택된 상품</p>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-800">{selectedProduct.name}</span>
                  <div className="text-right">
                    {couponResult && (
                      <p className="text-xs text-gray-400 line-through">{fmt(selectedProduct.price)}</p>
                    )}
                    <p className="font-bold text-blue-700">
                      {fmt(couponResult ? couponResult.finalAmount : selectedProduct.price)}
                    </p>
                    {couponResult && (
                      <p className="text-xs text-green-600 font-medium">
                        -{fmt(couponResult.discountAmount)} 할인
                      </p>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* 쿠폰 입력 */}
            {selectedProduct && (
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">쿠폰 코드</label>
                <CouponInput
                  userId={userId}
                  orderAmount={selectedProduct.price}
                  onApply={(result, code) => { setCouponResult(result); setAppliedCouponCode(code); }}
                  onRemove={() => { setCouponResult(null); setAppliedCouponCode(undefined); }}
                  appliedCode={appliedCouponCode}
                />
              </div>
            )}

            {/* 상품 리뷰 미리보기 */}
            {selectedProduct && (
              <div className="border border-gray-200 rounded-lg overflow-hidden">
                <button
                  type="button"
                  onClick={() => setReviewsOpen((o) => !o)}
                  className="w-full flex items-center justify-between px-4 py-3 bg-gray-50 hover:bg-gray-100 transition-colors text-sm"
                >
                  <span className="font-medium text-gray-700 flex items-center gap-2">
                    {loadingReviews ? (
                      <span className="text-gray-400">리뷰 불러오는 중...</span>
                    ) : reviews.length === 0 ? (
                      '상품 리뷰 (0개)'
                    ) : (
                      <>
                        <span>상품 리뷰 ({reviews.length}개)</span>
                        <StarRating
                          value={Math.round(reviews.reduce((s, r) => s + r.rating, 0) / reviews.length)}
                          size="sm"
                        />
                        <span className="text-yellow-600 font-semibold">
                          {(reviews.reduce((s, r) => s + r.rating, 0) / reviews.length).toFixed(1)}
                        </span>
                      </>
                    )}
                  </span>
                  <svg
                    className={`w-4 h-4 text-gray-400 transition-transform ${reviewsOpen ? 'rotate-180' : ''}`}
                    fill="none" stroke="currentColor" viewBox="0 0 24 24"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 9l-7 7-7-7" />
                  </svg>
                </button>
                {reviewsOpen && (
                  <div className="px-4 py-4 max-h-72 overflow-y-auto">
                    <ReviewList reviews={reviews} />
                  </div>
                )}
              </div>
            )}

            {/* 결제 수단 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">결제 수단</label>
              <select
                className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                value={paymentMethod}
                onChange={(e) => setPaymentMethod(e.target.value)}
              >
                <option value="CARD" className="text-gray-900">신용카드</option>
                <option value="BANK_TRANSFER" className="text-gray-900">계좌이체</option>
                <option value="VIRTUAL_ACCOUNT" className="text-gray-900">가상계좌</option>
                <option value="TOSS_PAYMENTS" className="text-gray-900">토스페이먼츠</option>
              </select>
            </div>

            {/* 토스페이먼츠 안내 */}
            {paymentMethod === 'TOSS_PAYMENTS' && selectedProduct && (
              <div className="flex items-center gap-2 bg-sky-50 border border-sky-200 rounded-lg p-3 text-sm text-sky-800">
                <svg className="w-4 h-4 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
                </svg>
                <span>주문하기를 누르면 토스페이먼츠 결제창이 열립니다.</span>
              </div>
            )}

            {loading ? (
              <Spinner size="md" message="주문 생성 중..." />
            ) : (
              <button
                onClick={handleCreateOrder}
                disabled={!selectedProduct}
                className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                {selectedProduct ? '주문하기' : '상품을 먼저 선택해주세요'}
              </button>
            )}
          </div>
        </Card>
      )}

      {/* Step 2: 주문 생성 완료 */}
      {step === 'order-created' && order && (
        <Card title="주문이 생성되었습니다">
          <div className="space-y-3 mb-6">
            {selectedProduct && (
              <div className="flex justify-between py-2.5 border-b">
                <span className="text-gray-500 text-sm">상품명</span>
                <span className="font-semibold text-sm">{selectedProduct.name}</span>
              </div>
            )}
            <div className="flex justify-between py-2.5 border-b">
              <span className="text-gray-500 text-sm">주문 번호</span>
              <span className="font-semibold text-sm">#{order.id}</span>
            </div>
            <div className="flex justify-between py-2.5 border-b">
              <span className="text-gray-500 text-sm">주문 금액</span>
              <div className="text-right">
                {couponResult && (
                  <p className="text-xs text-gray-400 line-through">{fmt(selectedProduct!.price)}</p>
                )}
                <span className="font-bold">{fmt(order.amount)}</span>
                {couponResult && (
                  <p className="text-xs text-green-600">{appliedCouponCode} 적용</p>
                )}
              </div>
            </div>
            <div className="flex justify-between py-2.5 border-b">
              <span className="text-gray-500 text-sm">주문 상태</span>
              <span className={`px-2.5 py-1 rounded-full text-xs font-semibold ${statusBadge(order.status)}`}>
                {order.status}
              </span>
            </div>
          </div>
          {loading ? <Spinner size="md" message="결제 정보 생성 중..." /> : (
            <button onClick={handleCreatePayment}
              className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors">
              결제 진행하기
            </button>
          )}
        </Card>
      )}

      {/* Step 3: 결제 준비 */}
      {step === 'payment-ready' && payment && (
        <Card title="결제 정보">
          <div className="space-y-3 mb-6">
            {[
              { label: '결제 번호', value: `#${payment.id}` },
              { label: '결제 금액', value: fmt(payment.amount), bold: true },
              { label: '결제 수단', value: payment.paymentMethod },
            ].map(({ label, value, bold }) => (
              <div key={label} className="flex justify-between py-2.5 border-b">
                <span className="text-gray-500 text-sm">{label}</span>
                <span className={bold ? 'font-bold' : 'font-semibold text-sm'}>{value}</span>
              </div>
            ))}
            <div className="flex justify-between py-2.5 border-b">
              <span className="text-gray-500 text-sm">결제 상태</span>
              <span className={`px-2.5 py-1 rounded-full text-xs font-semibold ${statusBadge(payment.status)}`}>
                {payment.status}
              </span>
            </div>
          </div>
          {loading ? <Spinner size="md" message="결제 처리 중..." /> : (
            <button onClick={handleAuthorizePayment}
              className="w-full bg-green-600 text-white py-3 rounded-lg font-semibold hover:bg-green-700 transition-colors">
              결제하기
            </button>
          )}
        </Card>
      )}

      {/* Step 4: 결제 완료 */}
      {step === 'completed' && payment && order && (
        <Card>
          <div className="text-center mb-6">
            <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-green-100 mb-4">
              <svg className="h-9 w-9 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h2 className="text-xl font-bold text-gray-900 mb-1">결제가 완료되었습니다!</h2>
            <p className="text-sm text-gray-500">정상적으로 처리되었습니다.</p>
          </div>
          <div className="bg-gray-50 rounded-xl p-5 mb-6 space-y-3">
            {selectedProduct && (
              <div className="flex justify-between text-sm">
                <span className="text-gray-500">상품명</span>
                <span className="font-semibold">{selectedProduct.name}</span>
              </div>
            )}
            <div className="flex justify-between text-sm">
              <span className="text-gray-500">주문 번호</span>
              <span className="font-semibold">#{order.id}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-gray-500">결제 번호</span>
              <span className="font-semibold">#{payment.id}</span>
            </div>
            {payment.pgTransactionId && (
              <div className="flex justify-between text-sm">
                <span className="text-gray-500">PG 거래 ID</span>
                <span className="font-mono text-xs text-gray-600 max-w-[180px] truncate">{payment.pgTransactionId}</span>
              </div>
            )}
            <div className="flex justify-between items-center pt-3 border-t border-gray-200">
              <span className="font-medium text-gray-900">결제 금액</span>
              <span className="text-xl font-bold text-green-600">{fmt(payment.amount)}</span>
            </div>
          </div>
          <button onClick={handleNewOrder}
            className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors">
            새로운 주문하기
          </button>
        </Card>
      )}
    </>
  );
};

/* ─────────────────────────────────────────
   메인 OrderPage
───────────────────────────────────────── */
const OrderPage: React.FC = () => {
  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-2xl mx-auto">
        <div className="text-center mb-6">
          <h1 className="text-3xl font-bold text-gray-900">주문하기</h1>
        </div>
        <OrderFormTab />
      </div>
    </div>
  );
};

export default OrderPage;