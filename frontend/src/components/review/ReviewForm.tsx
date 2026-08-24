import React, { useState } from 'react';
import StarRating from './StarRating';
import { reviewApi } from '@/api/review';
import { ReviewResponse } from '@/types';
import { apiErrorMessage } from '@/lib/apiError';

interface ReviewFormProps {
  productId: number;
  userId: number;
  existing?: ReviewResponse;           // 수정 모드일 때
  onSuccess: (review: ReviewResponse) => void;
  onCancel: () => void;
}

const ReviewForm: React.FC<ReviewFormProps> = ({
  productId, userId, existing, onSuccess, onCancel,
}) => {
  const isEdit = !!existing;
  const [rating, setRating]   = useState(existing?.rating ?? 0);
  const [content, setContent] = useState(existing?.content ?? '');
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (rating === 0) { setError('평점을 선택해주세요.'); return; }
    setLoading(true);
    setError(null);

    try {
      let result: ReviewResponse;
      if (isEdit) {
        result = await reviewApi.updateReview(existing!.id, { userId, rating, content });
      } else {
        result = await reviewApi.createReview({ productId, userId, rating, content });
      }
      onSuccess(result);
    } catch (err) {
      setError(apiErrorMessage(err, '리뷰 저장에 실패했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-800">
          {error}
        </div>
      )}

      {/* 별점 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">평점</label>
        <StarRating value={rating} onChange={setRating} size="lg" />
        <p className="text-xs text-gray-400 mt-1">
          {rating === 0 ? '별을 클릭해 평점을 선택하세요' : `${rating}점`}
        </p>
      </div>

      {/* 내용 */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          리뷰 내용 <span className="text-gray-400 font-normal">(선택)</span>
        </label>
        <textarea
          className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm text-gray-900 resize-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          rows={4}
          maxLength={1000}
          placeholder="상품에 대한 솔직한 리뷰를 남겨주세요."
          value={content}
          onChange={(e) => setContent(e.target.value)}
        />
        <p className="text-xs text-gray-400 text-right mt-1">{content.length}/1000</p>
      </div>

      {/* 버튼 */}
      <div className="flex gap-3">
        <button
          type="button"
          onClick={onCancel}
          className="flex-1 py-2.5 bg-gray-100 text-gray-700 rounded-lg font-semibold hover:bg-gray-200 transition-colors"
        >
          취소
        </button>
        <button
          type="submit"
          disabled={loading || rating === 0}
          className="flex-1 py-2.5 bg-blue-600 text-white rounded-lg font-semibold hover:bg-blue-700 transition-colors disabled:opacity-40"
        >
          {loading ? '저장 중...' : isEdit ? '수정하기' : '리뷰 등록'}
        </button>
      </div>
    </form>
  );
};

export default ReviewForm;
