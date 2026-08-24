import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import StarRating from '@/components/review/StarRating';

describe('StarRating', () => {
  it('별 5개를 그리고 값만큼 채운다', () => {
    const { container } = render(<StarRating value={3} />);

    expect(screen.getAllByRole('button')).toHaveLength(5);
    expect(container.querySelectorAll('svg.text-yellow-400')).toHaveLength(3);
    expect(container.querySelectorAll('svg.text-gray-300')).toHaveLength(2);
  });

  it('onChange 가 없으면 읽기 전용이라 버튼이 비활성이다', () => {
    render(<StarRating value={5} />);

    screen.getAllByRole('button').forEach((b) => expect(b).toBeDisabled());
  });

  it('onChange 를 주면 클릭한 별 점수를 알린다', async () => {
    const onChange = vi.fn();
    render(<StarRating value={0} onChange={onChange} />);

    await userEvent.click(screen.getAllByRole('button')[3]);

    expect(onChange).toHaveBeenCalledWith(4);
  });

  it.each([
    ['sm', 'w-4'],
    ['md', 'w-6'],
    ['lg', 'w-8'],
  ] as const)('크기 %s 는 %s 클래스를 쓴다', (size, cls) => {
    const { container } = render(<StarRating value={1} size={size} />);

    expect(container.querySelector(`svg.${cls}`)).not.toBeNull();
  });

  it('0점이면 채워진 별이 없다', () => {
    const { container } = render(<StarRating value={0} />);

    expect(container.querySelectorAll('svg.text-yellow-400')).toHaveLength(0);
  });
});
