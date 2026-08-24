import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ReviewList from '@/components/review/ReviewList';

const review = (over: Partial<Record<string, unknown>> = {}) =>
  ({
    id: 1,
    productId: 42,
    userId: 7,
    rating: 5,
    content: '좋아요',
    createdAt: '2026-08-01T00:00:00Z',
    ...over,
  }) as never;

describe('ReviewList', () => {
  it('리뷰가 없으면 빈 상태 문구를 보여 준다', () => {
    render(<ReviewList reviews={[]} />);

    expect(screen.getByText('아직 리뷰가 없습니다. 첫 리뷰를 남겨보세요!')).toBeInTheDocument();
  });

  it('평균 평점과 리뷰 수를 요약한다', () => {
    render(
      <ReviewList reviews={[review({ id: 1, rating: 5 }), review({ id: 2, rating: 4, userId: 8 })]} />,
    );

    expect(screen.getByText('4.5')).toBeInTheDocument();
    expect(screen.getByText('2개 리뷰')).toBeInTheDocument();
  });

  it('별점 분포 막대를 5행 그린다', () => {
    const { container } = render(<ReviewList reviews={[review()]} />);

    expect(container.querySelectorAll('.bg-yellow-400.h-1\\.5')).toHaveLength(5);
  });

  it('본인 리뷰에는 (나) 표시와 수정·삭제 버튼이 붙는다', () => {
    render(
      <ReviewList
        reviews={[review({ userId: 7 })]}
        currentUserId={7}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    expect(screen.getByText('(나)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수정' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '삭제' })).toBeInTheDocument();
  });

  it('남의 리뷰에는 수정·삭제 버튼이 없다', () => {
    render(
      <ReviewList
        reviews={[review({ userId: 99 })]}
        currentUserId={7}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    expect(screen.queryByRole('button', { name: '수정' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument();
  });

  it('핸들러가 없으면 본인 리뷰여도 버튼을 그리지 않는다', () => {
    render(<ReviewList reviews={[review({ userId: 7 })]} currentUserId={7} />);

    expect(screen.queryByRole('button', { name: '수정' })).not.toBeInTheDocument();
  });

  it('수정·삭제를 누르면 각각의 콜백에 대상이 전달된다', async () => {
    const onEdit = vi.fn();
    const onDelete = vi.fn();
    render(
      <ReviewList
        reviews={[review({ id: 5, userId: 7 })]}
        currentUserId={7}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: '수정' }));
    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    expect(onEdit).toHaveBeenCalledWith(expect.objectContaining({ id: 5 }));
    expect(onDelete).toHaveBeenCalledWith(5);
  });

  it('내용이 없는 리뷰는 본문을 그리지 않는다', () => {
    render(<ReviewList reviews={[review({ content: '' })]} />);

    expect(screen.queryByText('좋아요')).not.toBeInTheDocument();
    expect(screen.getByText('사용자 #7')).toBeInTheDocument();
  });

  it('작성일을 한국식으로 표기한다', () => {
    render(<ReviewList reviews={[review()]} />);

    expect(screen.getByText(/2026/)).toBeInTheDocument();
  });
});
