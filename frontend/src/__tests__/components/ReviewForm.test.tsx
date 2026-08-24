import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ReviewForm from '@/components/review/ReviewForm';
import { reviewApi } from '@/api/review';

vi.mock('@/api/review', () => ({
  reviewApi: {
    createReview: vi.fn(),
    updateReview: vi.fn(),
  },
}));

const base = {
  productId: 42,
  userId: 7,
  onSuccess: vi.fn(),
  onCancel: vi.fn(),
};

const existing = {
  id: 3,
  productId: 42,
  userId: 7,
  rating: 4,
  content: '괜찮아요',
  createdAt: '2026-08-01T00:00:00Z',
} as never;

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ReviewForm — 작성 모드', () => {
  it('평점 미선택이면 등록 버튼이 잠겨 있다', () => {
    render(<ReviewForm {...base} />);

    expect(screen.getByRole('button', { name: '리뷰 등록' })).toBeDisabled();
    expect(screen.getByText('별을 클릭해 평점을 선택하세요')).toBeInTheDocument();
  });

  it('별을 고르면 점수 안내가 바뀌고 등록이 열린다', async () => {
    render(<ReviewForm {...base} />);

    await userEvent.click(screen.getAllByRole('button')[4]);

    expect(screen.getByText('5점')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '리뷰 등록' })).toBeEnabled();
  });

  it('내용 글자 수를 세어 보여 준다', async () => {
    render(<ReviewForm {...base} />);

    await userEvent.type(screen.getByPlaceholderText(/솔직한 리뷰/), '좋아요');

    expect(screen.getByText('3/1000')).toBeInTheDocument();
  });

  it('등록하면 createReview 를 호출하고 결과를 알린다', async () => {
    vi.mocked(reviewApi.createReview).mockResolvedValueOnce({ ...(existing as object), id: 9 } as never);
    render(<ReviewForm {...base} />);

    await userEvent.click(screen.getAllByRole('button')[4]);
    await userEvent.type(screen.getByPlaceholderText(/솔직한 리뷰/), '좋아요');
    await userEvent.click(screen.getByRole('button', { name: '리뷰 등록' }));

    await waitFor(() =>
      expect(reviewApi.createReview).toHaveBeenCalledWith({
        productId: 42,
        userId: 7,
        rating: 5,
        content: '좋아요',
      }),
    );
    expect(base.onSuccess).toHaveBeenCalled();
  });

  it('저장이 실패하면 오류 문구를 보여 준다', async () => {
    vi.mocked(reviewApi.createReview).mockRejectedValueOnce(new Error('boom'));
    render(<ReviewForm {...base} />);

    await userEvent.click(screen.getAllByRole('button')[2]);
    await userEvent.click(screen.getByRole('button', { name: '리뷰 등록' }));

    expect(await screen.findByText('리뷰 저장에 실패했습니다.')).toBeInTheDocument();
    expect(base.onSuccess).not.toHaveBeenCalled();
  });

  it('취소를 누르면 onCancel 이 불린다', async () => {
    render(<ReviewForm {...base} />);

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(base.onCancel).toHaveBeenCalledTimes(1);
  });
});

describe('ReviewForm — 수정 모드', () => {
  it('기존 값을 채워 놓고 수정하기 버튼을 보여 준다', () => {
    render(<ReviewForm {...base} existing={existing} />);

    expect(screen.getByPlaceholderText(/솔직한 리뷰/)).toHaveValue('괜찮아요');
    expect(screen.getByText('4점')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수정하기' })).toBeEnabled();
  });

  it('수정하면 updateReview 를 호출한다', async () => {
    vi.mocked(reviewApi.updateReview).mockResolvedValueOnce(existing);
    render(<ReviewForm {...base} existing={existing} />);

    await userEvent.click(screen.getByRole('button', { name: '수정하기' }));

    await waitFor(() =>
      expect(reviewApi.updateReview).toHaveBeenCalledWith(3, {
        userId: 7,
        rating: 4,
        content: '괜찮아요',
      }),
    );
  });
});
