import React from 'react';
import StarRating from './StarRating';
import { ReviewResponse } from '@/types';

interface ReviewListProps {
  reviews: ReviewResponse[];
  currentUserId?: number;
  onEdit?: (review: ReviewResponse) => void;
  onDelete?: (reviewId: number) => void;
}

const ReviewList: React.FC<ReviewListProps> = ({
  reviews, currentUserId, onEdit, onDelete,
}) => {
  if (reviews.length === 0) {
    return (
      <div className="text-center py-10 text-gray-400">
        <svg className="mx-auto h-10 w-10 mb-2 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"
            d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
        </svg>
        <p className="text-sm">아직 리뷰가 없습니다. 첫 리뷰를 남겨보세요!</p>
      </div>
    );
  }

  const avg = reviews.reduce((s, r) => s + r.rating, 0) / reviews.length;
  const dist = [1, 2, 3, 4, 5].map((n) => ({
    star: n,
    count: reviews.filter((r) => r.rating === n).length,
  }));
  const max = Math.max(...dist.map((d) => d.count), 1);

  return (
    <div className="space-y-5">
      {/* 요약 */}
      <div className="bg-gray-50 rounded-xl p-5 flex gap-6 items-center">
        <div className="text-center flex-shrink-0">
          <p className="text-4xl font-bold text-gray-900">{avg.toFixed(1)}</p>
          <StarRating value={Math.round(avg)} size="sm" />
          <p className="text-xs text-gray-400 mt-1">{reviews.length}개 리뷰</p>
        </div>
        <div className="flex-1 space-y-1.5">
          {[5, 4, 3, 2, 1].map((star) => {
            const count = dist.find((d) => d.star === star)?.count ?? 0;
            return (
              <div key={star} className="flex items-center gap-2 text-xs">
                <span className="text-gray-500 w-3">{star}</span>
                <svg className="w-3 h-3 text-yellow-400 fill-current flex-shrink-0" viewBox="0 0 20 20">
                  <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                </svg>
                <div className="flex-1 bg-gray-200 rounded-full h-1.5">
                  <div
                    className="bg-yellow-400 h-1.5 rounded-full transition-all"
                    style={{ width: `${(count / max) * 100}%` }}
                  />
                </div>
                <span className="text-gray-400 w-3 text-right">{count}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* 리뷰 목록 */}
      {reviews.map((review) => {
        const isOwn = currentUserId != null && review.userId === currentUserId;
        return (
          <div key={review.id} className="border-b border-gray-100 pb-4 last:border-0">
            <div className="flex items-start justify-between mb-1">
              <div className="flex items-center gap-2">
                <div className="w-7 h-7 rounded-full bg-blue-100 flex items-center justify-center flex-shrink-0">
                  <svg className="w-4 h-4 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                      d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                  </svg>
                </div>
                <span className="text-sm font-medium text-gray-700">
                  사용자 #{review.userId}
                  {isOwn && <span className="ml-1.5 text-xs text-blue-500">(나)</span>}
                </span>
              </div>
              <div className="flex items-center gap-2">
                <StarRating value={review.rating} size="sm" />
                {isOwn && onEdit && onDelete && (
                  <div className="flex gap-1 ml-2">
                    <button
                      onClick={() => onEdit(review)}
                      className="text-xs text-gray-400 hover:text-blue-500 transition-colors"
                    >
                      수정
                    </button>
                    <span className="text-gray-300">|</span>
                    <button
                      onClick={() => onDelete(review.id)}
                      className="text-xs text-gray-400 hover:text-red-500 transition-colors"
                    >
                      삭제
                    </button>
                  </div>
                )}
              </div>
            </div>
            {review.content && (
              <p className="text-sm text-gray-900 mt-2 leading-relaxed ml-9">{review.content}</p>
            )}
            <p className="text-xs text-gray-400 mt-2 ml-9">
              {new Date(review.createdAt).toLocaleDateString('ko-KR', {
                year: 'numeric', month: 'short', day: 'numeric',
              })}
            </p>
          </div>
        );
      })}
    </div>
  );
};

export default ReviewList;
