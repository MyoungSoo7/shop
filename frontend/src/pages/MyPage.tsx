import React, { useEffect, useState } from 'react';
import { orderApi } from '@/api/order';
import { productApi } from '@/api/product';
import { reviewApi } from '@/api/review';
import { authApi } from '@/api/auth';
import { OrderResponse, ProductResponse, ReviewResponse } from '@/types';
import Spinner from '@/components/Spinner';
import StarRating from '@/components/review/StarRating';
import ReviewForm from '@/components/review/ReviewForm';
import ReviewList from '@/components/review/ReviewList';
import CashReceiptPanel from '@/components/CashReceiptPanel';

const USER_ID = 1;

type Tab = 'orders' | 'reviews';

const MyPage: React.FC = () => {
  const user = authApi.getCurrentUser();
  const [activeTab, setActiveTab] = useState<Tab>('orders');

  // 주문 데이터
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [products, setProducts] = useState<Map<number, ProductResponse>>(new Map());
  const [loadingOrders, setLoadingOrders] = useState(true);
  const [ordersError, setOrdersError] = useState<string | null>(null);

  // 리뷰 데이터
  const [myReviews, setMyReviews] = useState<ReviewResponse[]>([]);
  const [loadingReviews, setLoadingReviews] = useState(false);

  // 리뷰 폼 모달
  const [reviewFormTarget, setReviewFormTarget] = useState<{
    productId: number;
    productName: string;
    existing?: ReviewResponse;
  } | null>(null);

  useEffect(() => {
    const load = async () => {
      try {
        const [orderList, productList, reviewList] = await Promise.all([
          orderApi.getUserOrders(USER_ID),
          productApi.getAllProducts(),
          reviewApi.getUserReviews(USER_ID),
        ]);
        const productMap = new Map(productList.map((p) => [p.id, p]));
        setOrders(orderList.sort((a, b) => b.id - a.id));
        setProducts(productMap);
        setMyReviews(reviewList);
      } catch {
        setOrdersError('주문 목록을 불러오지 못했습니다.');
      } finally {
        setLoadingOrders(false);
      }
    };
    load();
  }, []);

  const loadMyReviews = () => {
    setLoadingReviews(true);
    reviewApi.getUserReviews(USER_ID)
      .then(setMyReviews)
      .catch(() => {})
      .finally(() => setLoadingReviews(false));
  };

  useEffect(() => {
    if (activeTab === 'reviews') loadMyReviews();
  }, [activeTab]);

  const formatCurrency = (v: number) =>
    new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

  const formatDate = (s: string) =>
    new Date(s).toLocaleDateString('ko-KR', {
      year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    });

  const statusConfig: Record<string, { label: string; cls: string }> = {
    CREATED:  { label: '주문 완료', cls: 'bg-yellow-100 text-yellow-800' },
    PAID:     { label: '결제 완료', cls: 'bg-green-100 text-green-800' },
    CANCELED: { label: '취소됨',   cls: 'bg-red-100 text-red-800' },
    REFUNDED: { label: '환불됨',   cls: 'bg-purple-100 text-purple-800' },
  };

  // 리뷰 성공 처리
  const handleReviewSuccess = (saved: ReviewResponse) => {
    setReviewFormTarget(null);
    setMyReviews((prev) => {
      const idx = prev.findIndex((r) => r.id === saved.id);
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = saved;
        return next;
      }
      return [saved, ...prev];
    });
  };

  // 리뷰 삭제
  const handleDeleteReview = async (reviewId: number) => {
    if (!window.confirm('리뷰를 삭제하시겠습니까?')) return;
    try {
      await reviewApi.deleteReview(reviewId, USER_ID);
      setMyReviews((prev) => prev.filter((r) => r.id !== reviewId));
    } catch {
      alert('리뷰 삭제에 실패했습니다.');
    }
  };

  // 주문에 해당 상품 리뷰가 있는지 확인
  const getReviewForProduct = (productId?: number) =>
    productId ? myReviews.find((r) => r.productId === productId) : undefined;

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-blue-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-2xl mx-auto space-y-6">

        {/* 사용자 정보 카드 */}
        <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-6">
          <div className="flex items-center gap-4">
            <div className="w-16 h-16 rounded-full bg-blue-100 flex items-center justify-center flex-shrink-0">
              <svg className="w-8 h-8 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8"
                  d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
              </svg>
            </div>
            <div>
              <h2 className="text-xl font-bold text-gray-900">{user?.email ?? '사용자'}</h2>
              <span className="inline-block mt-1 px-2.5 py-0.5 bg-blue-100 text-blue-800 text-xs font-semibold rounded-full">
                {user?.role ?? 'USER'}
              </span>
            </div>
          </div>
        </div>

        {/* 탭 */}
        <div className="flex bg-white rounded-xl shadow-sm border border-gray-200 p-1">
          {([
            { id: 'orders', label: '주문 내역' },
            { id: 'reviews', label: '내 리뷰' },
          ] as { id: Tab; label: string }[]).map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex-1 py-2.5 text-sm font-semibold rounded-lg transition-all ${
                activeTab === tab.id
                  ? 'bg-blue-600 text-white shadow'
                  : 'text-gray-500 hover:text-gray-800'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* ── 주문 내역 탭 ── */}
        {activeTab === 'orders' && (
          loadingOrders ? (
            <Spinner size="md" message="주문 목록 불러오는 중..." />
          ) : ordersError ? (
            <p className="text-center text-red-600 py-8">{ordersError}</p>
          ) : orders.length === 0 ? (
            <div className="text-center py-16 text-gray-400 bg-white rounded-xl border border-gray-200">
              <svg className="mx-auto h-12 w-12 mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
                  d="M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z" />
              </svg>
              <p className="text-sm">주문 내역이 없습니다.</p>
            </div>
          ) : (
            <div className="space-y-4">
              {orders.map((order) => {
                const product = order.productId ? products.get(order.productId) : null;
                const status  = statusConfig[order.status] ?? { label: order.status, cls: 'bg-gray-100 text-gray-700' };
                const existingReview = getReviewForProduct(order.productId);
                const canReview = order.status === 'PAID' && order.productId;

                return (
                  <div key={order.id} className="bg-white rounded-xl border border-gray-200 p-5 shadow-sm">
                    <div className="flex items-start justify-between mb-3">
                      <div className="flex items-center gap-3">
                        {product?.primaryImageUrl ? (
                          <img src={product.primaryImageUrl} alt={product.name}
                            className="w-12 h-12 rounded-lg object-cover flex-shrink-0" />
                        ) : (
                          <div className="w-12 h-12 rounded-lg bg-gray-100 flex items-center justify-center flex-shrink-0">
                            <svg className="w-6 h-6 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
                                d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
                            </svg>
                          </div>
                        )}
                        <div>
                          <p className="font-semibold text-gray-900">
                            {product ? product.name : `상품 #${order.productId ?? '-'}`}
                          </p>
                          <p className="text-xs text-gray-500 mt-0.5">주문 #{order.id}</p>
                        </div>
                      </div>
                      <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${status.cls}`}>
                        {status.label}
                      </span>
                    </div>

                    <div className="flex justify-between items-center pt-3 border-t border-gray-100">
                      <span className="text-xs text-gray-400">{formatDate(order.createdAt)}</span>
                      <span className="text-base font-bold text-blue-700">{formatCurrency(order.amount)}</span>
                    </div>

                    {/* 리뷰 버튼 (결제 완료된 주문에만) */}
                    {canReview && (
                      <div className="mt-3 pt-3 border-t border-gray-100">
                        {existingReview ? (
                          <div className="flex items-center justify-between">
                            <div className="flex items-center gap-2">
                              <StarRating value={existingReview.rating} size="sm" />
                              <span className="text-xs text-gray-500">내 리뷰</span>
                            </div>
                            <button
                              onClick={() => setReviewFormTarget({
                                productId: order.productId!,
                                productName: product?.name ?? `상품 #${order.productId}`,
                                existing: existingReview,
                              })}
                              className="text-xs text-blue-500 hover:text-blue-700 font-medium"
                            >
                              수정하기
                            </button>
                          </div>
                        ) : (
                          <button
                            onClick={() => {
                              setActiveTab('orders'); // 주문 탭 유지
                              setReviewFormTarget({
                                productId: order.productId!,
                                productName: product?.name ?? `상품 #${order.productId}`,
                              });
                              // 내 리뷰 목록 미리 로드
                              if (myReviews.length === 0) loadMyReviews();
                            }}
                            className="w-full text-center text-xs font-semibold text-blue-600 hover:text-blue-800 py-1.5 rounded-lg bg-blue-50 hover:bg-blue-100 transition-colors"
                          >
                            리뷰 작성하기
                          </button>
                        )}
                      </div>
                    )}

                    {/* 현금영수증 — 계좌이체·가상계좌 결제만 대상(카드는 카드사 전표로 이미 신고됨).
                        결제된 주문에만 노출한다: 입금 전에는 발급할 것이 없다. */}
                    {order.status === 'PAID' && <CashReceiptPanel orderId={order.id} />}
                  </div>
                );
              })}
            </div>
          )
        )}

        {/* ── 내 리뷰 탭 ── */}
        {activeTab === 'reviews' && (
          loadingReviews ? (
            <Spinner size="md" message="리뷰 불러오는 중..." />
          ) : myReviews.length === 0 ? (
            <div className="text-center py-16 text-gray-400 bg-white rounded-xl border border-gray-200">
              <svg className="mx-auto h-12 w-12 mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
                  d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
              </svg>
              <p className="text-sm">작성한 리뷰가 없습니다.</p>
              <p className="text-xs mt-1 text-gray-300">결제 완료된 주문에서 리뷰를 작성해보세요.</p>
            </div>
          ) : (
            <div className="bg-white rounded-xl border border-gray-200 p-5">
              <ReviewList
                reviews={myReviews}
                currentUserId={USER_ID}
                onEdit={(review) => {
                  const product = products.get(review.productId);
                  setReviewFormTarget({
                    productId: review.productId,
                    productName: product?.name ?? `상품 #${review.productId}`,
                    existing: review,
                  });
                }}
                onDelete={handleDeleteReview}
              />
            </div>
          )
        )}
      </div>

      {/* 리뷰 작성/수정 모달 */}
      {reviewFormTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40"
          onClick={(e) => { if (e.target === e.currentTarget) setReviewFormTarget(null); }}
        >
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-md p-6">
            <h3 className="text-lg font-bold text-gray-900 mb-1">
              {reviewFormTarget.existing ? '리뷰 수정' : '리뷰 작성'}
            </h3>
            <p className="text-sm text-gray-500 mb-5">{reviewFormTarget.productName}</p>
            <ReviewForm
              productId={reviewFormTarget.productId}
              userId={USER_ID}
              existing={reviewFormTarget.existing}
              onSuccess={handleReviewSuccess}
              onCancel={() => setReviewFormTarget(null)}
            />
          </div>
        </div>
      )}
    </div>
  );
};

export default MyPage;