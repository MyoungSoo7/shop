import React from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';

const TossPaymentFail: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const errorCode    = searchParams.get('code') || '알 수 없음';
  const errorMessage = searchParams.get('message') || '결제가 취소되었습니다.';

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-red-50 to-pink-50 px-4">
      <div className="bg-white rounded-2xl shadow-lg p-8 max-w-md w-full text-center">
        <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-red-100 mb-4">
          <svg className="h-10 w-10 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </div>

        <h2 className="text-2xl font-bold text-gray-900 mb-2">결제 실패</h2>
        <p className="text-gray-600 mb-2">{errorMessage}</p>
        <p className="text-xs text-gray-400 mb-8">오류 코드: {errorCode}</p>

        <button
          onClick={() => navigate('/order')}
          className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors"
        >
          다시 시도하기
        </button>
      </div>
    </div>
  );
};

export default TossPaymentFail;