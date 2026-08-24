import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import LoadingSkeleton from '@/components/LoadingSkeleton';

describe('LoadingSkeleton', () => {
  it('기본은 테이블 스켈레톤이며 헤더 + 기본 5행을 그린다', () => {
    const { container } = render(<LoadingSkeleton />);

    // 헤더 1줄 + 본문 5줄 = 6줄, 각 줄 8칸
    expect(container.querySelectorAll('.h-4.bg-gray-200').length).toBe(6 * 8);
    expect(container.querySelector('.animate-pulse')).not.toBeNull();
  });

  it('테이블 행 수를 조절할 수 있다', () => {
    const { container } = render(<LoadingSkeleton type="table" rows={2} />);

    expect(container.querySelectorAll('.h-4.bg-gray-200').length).toBe(3 * 8);
  });

  it('카드 스켈레톤은 3장을 그린다 (rows 와 무관)', () => {
    const { container } = render(<LoadingSkeleton type="card" rows={9} />);

    expect(container.querySelectorAll('.animate-pulse').length).toBe(3);
    expect(container.querySelectorAll('.h-8.bg-gray-200').length).toBe(3);
  });

  it('텍스트 스켈레톤은 rows 만큼 줄을 그린다', () => {
    const { container } = render(<LoadingSkeleton type="text" rows={3} />);

    expect(container.querySelectorAll('.h-4.bg-gray-200').length).toBe(3);
  });

  it('텍스트 스켈레톤 기본 행 수는 5', () => {
    const { container } = render(<LoadingSkeleton type="text" />);

    expect(container.querySelectorAll('.h-4.bg-gray-200').length).toBe(5);
  });
});
