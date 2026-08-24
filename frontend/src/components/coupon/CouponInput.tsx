import React, { useState } from 'react';
import { couponApi } from '@/api/coupon';
import { CouponValidateResponse } from '@/types';

interface CouponInputProps {
  userId: number;
  orderAmount: number;
  onApply: (result: CouponValidateResponse, code: string) => void;
  onRemove: () => void;
  appliedCode?: string;
}

const CouponInput: React.FC<CouponInputProps> = ({
  userId,
  orderAmount,
  onApply,
  onRemove,
  appliedCode,
}) => {
  const [code, setCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleApply = async () => {
    if (!code.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const result = await couponApi.validate(code.trim(), userId, orderAmount);
      if (result.valid) {
        onApply(result, code.trim().toUpperCase());
        setCode('');
      } else {
        setError(result.message);
      }
    } catch {
      setError('쿠폰 확인 중 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleApply();
    }
  };

  if (appliedCode) {
    return (
      <div className="flex items-center gap-2 p-3 bg-green-50 border border-green-200 rounded-lg">
        <svg className="w-4 h-4 text-green-600 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
        </svg>
        <span className="text-sm font-medium text-green-800 flex-1">
          쿠폰 적용됨: <span className="font-bold">{appliedCode}</span>
        </span>
        <button
          type="button"
          onClick={onRemove}
          className="text-xs text-gray-500 hover:text-red-600 underline"
        >
          취소
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex gap-2">
        <input
          type="text"
          value={code}
          onChange={(e) => { setCode(e.target.value.toUpperCase()); setError(null); }}
          onKeyDown={handleKeyDown}
          placeholder="쿠폰 코드 입력 (예: WELCOME10)"
          className="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          disabled={loading}
        />
        <button
          type="button"
          onClick={handleApply}
          disabled={loading || !code.trim()}
          className="px-4 py-2 text-sm font-medium bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {loading ? '확인 중...' : '적용'}
        </button>
      </div>
      {error && (
        <p className="text-xs text-red-600">{error}</p>
      )}
    </div>
  );
};

export default CouponInput;