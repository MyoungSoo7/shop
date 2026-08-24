import React from 'react';

interface StarRatingProps {
  value: number;           // 현재 값 (0~5)
  onChange?: (v: number) => void; // interactive 모드 (없으면 readonly)
  size?: 'sm' | 'md' | 'lg';
}

const SIZE = { sm: 'w-4 h-4', md: 'w-6 h-6', lg: 'w-8 h-8' };

const StarRating: React.FC<StarRatingProps> = ({ value, onChange, size = 'md' }) => {
  const interactive = !!onChange;
  const cls = SIZE[size];

  return (
    <div className="flex gap-0.5">
      {[1, 2, 3, 4, 5].map((star) => (
        <button
          key={star}
          type="button"
          disabled={!interactive}
          onClick={() => onChange?.(star)}
          className={`${interactive ? 'cursor-pointer hover:scale-110 transition-transform' : 'cursor-default'}`}
        >
          <svg
            className={`${cls} ${star <= value ? 'text-yellow-400' : 'text-gray-300'} fill-current`}
            viewBox="0 0 20 20"
          >
            <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
          </svg>
        </button>
      ))}
    </div>
  );
};

export default StarRating;