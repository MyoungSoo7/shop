import React, { useCallback, useEffect, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { paymentApi } from '@/api/payment';
import { PaymentResponse } from '@/types';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

const TossPaymentSuccess: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  // 단건 or 장바구니 모두 결과를 배열로 통일
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [countdown, setCountdown] = useState(3);

  const goToHistory = useCallback(() => navigate('/mypage'), [navigate]);

  // 쿼리 파라미터는 effect 밖에서 읽는다 — 의존성을 searchParams 객체가 아니라 문자열 값으로 두기
  // 위해서다. 객체를 의존성에 넣으면 identity 가 바뀔 때 결제 확인 POST 가 재실행될 수 있다.
  const paymentKey  = searchParams.get('paymentKey');
  const tossOrderId = searchParams.get('orderId');
  const amountStr   = searchParams.get('amount');
  const type        = searchParams.get('type');        // 'cart' | null
  const dbOrderIds  = searchParams.get('dbOrderIds');  // 장바구니: '1,2,3'
  const dbOrderId   = searchParams.get('dbOrderId');   // 단건: '1'

  useEffect(() => {
    if (!paymentKey || !tossOrderId || !amountStr) {
      setError('결제 정보가 올바르지 않습니다.');
      setLoading(false);
      return;
    }

    if (type === 'cart' && dbOrderIds) {
      // ── 장바구니 일괄 결제 확인 ──
      const orderIds = dbOrderIds.split(',').map(Number).filter(Boolean);
      paymentApi
        .confirmTossCartPayment({
          orderIds,
          paymentKey,
          tossOrderId,
          totalAmount: Number(amountStr),
        })
        .then((res) => setPayments(res))
        .catch((err) => {
          setError(apiErrorMessage(err, '결제 확인 중 오류가 발생했습니다.'));
        })
        .finally(() => setLoading(false));
    } else if (dbOrderId) {
      // ── 단건 결제 확인 ──
      paymentApi
        .confirmTossPayment({
          dbOrderId: Number(dbOrderId),
          paymentKey,
          tossOrderId,
          amount: Number(amountStr),
        })
        .then((res) => setPayments([res]))
        .catch((err) => {
          setError(apiErrorMessage(err, '결제 확인 중 오류가 발생했습니다.'));
        })
        .finally(() => setLoading(false));
    } else {
      setError('결제 정보가 올바르지 않습니다.');
      setLoading(false);
    }
  }, [paymentKey, tossOrderId, amountStr, type, dbOrderIds, dbOrderId]);

  /* 결제 성공 후 3초 카운트다운 → 주문 상품 탭으로 자동 이동 */
  useEffect(() => {
    if (payments.length === 0) return;
    const interval = setInterval(() => {
      setCountdown((c) => {
        if (c <= 1) {
          clearInterval(interval);
          goToHistory();
          return 0;
        }
        return c - 1;
      });
    }, 1000);
    return () => clearInterval(interval);
  }, [payments, goToHistory]);

  const formatCurrency = (value: number) =>
    new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(value);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-50">
        <Spinner size="lg" message="결제 확인 중..." />
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-red-50 to-pink-50 px-4">
        <div className="bg-white rounded-2xl shadow-lg p-8 max-w-md w-full text-center">
          <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-red-100 mb-4">
            <svg className="h-10 w-10 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </div>
          <h2 className="text-xl font-bold text-gray-900 mb-2">결제 확인 실패</h2>
          <p className="text-gray-600 mb-6">{error}</p>
          <button
            onClick={() => navigate('/order')}
            className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors"
          >
            주문 페이지로 돌아가기
          </button>
        </div>
      </div>
    );
  }

  const isCart = payments.length > 1;
  const totalPaid = payments.reduce((s, p) => s + p.amount, 0);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-green-50 to-emerald-50 px-4">
      <div className="bg-white rounded-2xl shadow-lg p-8 max-w-md w-full">

        {/* 성공 헤더 */}
        <div className="text-center mb-6">
          <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-green-100 mb-4">
            <svg className="h-10 w-10 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <h2 className="text-2xl font-bold text-gray-900 mb-1">결제 완료!</h2>
          <p className="text-gray-500 text-sm">
            {isCart
              ? `${payments.length}개 상품이 성공적으로 결제되었습니다.`
              : '토스페이먼츠 결제가 성공적으로 처리되었습니다.'}
          </p>
        </div>

        {/* 결제 내역 */}
        <div className="bg-gray-50 rounded-xl p-5 mb-6 space-y-3">
          {isCart ? (
            /* 장바구니: 주문별 목록 */
            <>
              {payments.map((p) => (
                <div key={p.id} className="flex justify-between text-sm">
                  <span className="text-gray-500">주문 #{p.orderId} 결제</span>
                  <span className="font-semibold text-gray-900">{formatCurrency(p.amount)}</span>
                </div>
              ))}
              <div className="flex justify-between items-center pt-3 border-t border-gray-200">
                <span className="font-bold text-gray-900">총 결제 금액</span>
                <span className="text-2xl font-bold text-green-600">{formatCurrency(totalPaid)}</span>
              </div>
            </>
          ) : (
            /* 단건: 기존 상세 */
            <>
              <div className="flex justify-between text-sm">
                <span className="text-gray-500">결제 번호</span>
                <span className="font-semibold text-gray-900">#{payments[0].id}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-500">결제 수단</span>
                <span className="font-semibold text-gray-900">토스페이먼츠</span>
              </div>
              {payments[0].pgTransactionId && (
                <div className="flex justify-between text-sm">
                  <span className="text-gray-500">PG 거래 ID</span>
                  <span className="font-mono text-xs text-gray-700 truncate max-w-[180px]">
                    {payments[0].pgTransactionId}
                  </span>
                </div>
              )}
              <div className="flex justify-between items-center pt-3 border-t border-gray-200">
                <span className="text-gray-900 font-medium">결제 금액</span>
                <span className="text-2xl font-bold text-green-600">{formatCurrency(payments[0].amount)}</span>
              </div>
            </>
          )}
        </div>

        {/* 카운트다운 + 이동 버튼 */}
        <p className="text-center text-sm text-gray-400 mb-4">
          {countdown}초 후 주문 상품 탭으로 자동 이동합니다...
        </p>
        <button
          onClick={goToHistory}
          className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors"
        >
          주문 상품 보기
        </button>
      </div>
    </div>
  );
};

export default TossPaymentSuccess;