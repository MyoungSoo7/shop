import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Inquiry } from '@/api/inquiry';

/**
 * 문의 응대 콘솔이 지켜야 하는 규율.
 *
 * <p>① <b>대기열은 오래된 순이다.</b> 서버가 그 순서로 주고, 화면은 다시 정렬하지 않는다. 최신순으로
 * 두면 오래 기다린 문의가 목록 끝으로 밀려 영영 답을 못 받는다.
 *
 * <p>② <b>목록의 행을 그대로 열지 않는다.</b> 비밀글 본문은 상세 경로로만 온다.
 *
 * <p>③ <b>답변을 지우면 그 문의가 대기열로 돌아온다.</b> 상태는 저장된 칼럼이 아니라 답변 유무라,
 * 서버가 돌려주는 것은 "지웠다"가 아니라 지운 뒤의 문의다.
 *
 * <p>④ <b>답변자를 보내지 않는다.</b> 토큰이 정한다.
 */

vi.mock('@/api/inquiry', () => ({
  adminInquiryApi: { listWaiting: vi.fn(), get: vi.fn(), answer: vi.fn(), deleteAnswer: vi.fn() },
}));

const { adminInquiryApi } = await import('@/api/inquiry');
const { default: InquiryAdminPage } = await import('@/pages/InquiryAdminPage');
const mocked = vi.mocked(adminInquiryApi);

const older: Inquiry = {
  id: 1,
  userId: 7,
  type: 'PRODUCT',
  typeLabel: '상품 문의',
  productId: 10,
  orderId: null,
  subject: '정사이즈인가요',
  content: '평소 270 신습니다',
  secret: false,
  readable: true,
  status: 'WAITING',
  statusLabel: '답변 대기',
  askedAt: '2026-08-18T10:00:00',
  answers: [],
};

const newer: Inquiry = { ...older, id: 2, subject: '색상 문의', askedAt: '2026-08-20T10:00:00' };

const secret: Inquiry = {
  ...older,
  id: 3,
  secret: true,
  readable: false,
  subject: '비밀글입니다',
  content: '',
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.listWaiting.mockResolvedValue([]);
});

describe('InquiryAdminPage', () => {
  it('대기열이 비면 비어 있다고 말한다', async () => {
    render(<InquiryAdminPage />);

    expect(await screen.findByTestId('waiting-empty')).toBeInTheDocument();
    expect(await screen.findByTestId('detail-empty')).toBeInTheDocument();
  });

  it('서버가 준 순서를 그대로 그린다 — 화면에서 최신순으로 다시 정렬하지 않는다', async () => {
    mocked.listWaiting.mockResolvedValue([older, newer]);
    render(<InquiryAdminPage />);

    const rows = await screen.findAllByRole('button', { name: /문의/ });
    expect(rows[0]).toHaveAttribute('data-testid', 'waiting-1');
    expect(rows[1]).toHaveAttribute('data-testid', 'waiting-2');
  });

  it('목록의 행을 그대로 열지 않고 상세를 다시 읽는다 — 비밀글 본문은 이 경로로만 온다', async () => {
    mocked.listWaiting.mockResolvedValue([secret]);
    mocked.get.mockResolvedValue({ ...secret, readable: true, subject: '재고 있나요', content: '언제 들어오나요' });
    render(<InquiryAdminPage />);

    await userEvent.click(await screen.findByTestId('waiting-3'));

    expect(mocked.get).toHaveBeenCalledWith(3);
    expect(await screen.findByText('언제 들어오나요')).toBeInTheDocument();
  });

  it('답변 등록은 답변자를 보내지 않는다', async () => {
    mocked.listWaiting.mockResolvedValue([older]);
    mocked.get.mockResolvedValue(older);
    mocked.answer.mockResolvedValue({
      ...older,
      status: 'ANSWERED',
      statusLabel: '답변 완료',
      answers: [{ id: 100, answeredBy: 3, content: '정사이즈입니다', answeredAt: '2026-08-21T09:00:00' }],
    });
    render(<InquiryAdminPage />);

    await userEvent.click(await screen.findByTestId('waiting-1'));
    await userEvent.type(await screen.findByLabelText('답변 내용'), '정사이즈입니다');
    await userEvent.click(screen.getByRole('button', { name: '답변 등록' }));

    await waitFor(() => expect(mocked.answer).toHaveBeenCalledWith(1, '정사이즈입니다'));
    expect(await screen.findByTestId('detail-status')).toHaveTextContent('답변 완료');
  });

  it('답변을 달면 대기열을 다시 읽는다 — 방금 답한 문의가 남아 있지 않게', async () => {
    mocked.listWaiting.mockResolvedValue([older]);
    mocked.get.mockResolvedValue(older);
    mocked.answer.mockResolvedValue({ ...older, status: 'ANSWERED', statusLabel: '답변 완료', answers: [] });
    render(<InquiryAdminPage />);

    await userEvent.click(await screen.findByTestId('waiting-1'));
    await userEvent.type(await screen.findByLabelText('답변 내용'), '답변');
    await userEvent.click(screen.getByRole('button', { name: '답변 등록' }));

    await waitFor(() => expect(mocked.listWaiting).toHaveBeenCalledTimes(2));
  });

  it('답변 삭제는 어느 문의의 답변인지까지 보낸다', async () => {
    const withAnswer: Inquiry = {
      ...older,
      status: 'ANSWERED',
      statusLabel: '답변 완료',
      answers: [{ id: 100, answeredBy: 3, content: '정사이즈입니다', answeredAt: '2026-08-21T09:00:00' }],
    };
    mocked.listWaiting.mockResolvedValue([older]);
    mocked.get.mockResolvedValue(withAnswer);
    mocked.deleteAnswer.mockResolvedValue(older);
    render(<InquiryAdminPage />);

    await userEvent.click(await screen.findByTestId('waiting-1'));
    await userEvent.click(await screen.findByRole('button', { name: '답변 삭제' }));

    expect(mocked.deleteAnswer).toHaveBeenCalledWith(1, 100);
  });

  /** 상태는 저장된 칼럼이 아니라 답변 유무다 — 지우는 즉시 그 문의가 대기로 돌아온다. */
  it('답변을 지우면 상세가 다시 대기가 되고 대기열도 다시 읽는다', async () => {
    const withAnswer: Inquiry = {
      ...older,
      status: 'ANSWERED',
      statusLabel: '답변 완료',
      answers: [{ id: 100, answeredBy: 3, content: '정사이즈입니다', answeredAt: '2026-08-21T09:00:00' }],
    };
    mocked.listWaiting.mockResolvedValue([older]);
    mocked.get.mockResolvedValue(withAnswer);
    mocked.deleteAnswer.mockResolvedValue(older);
    render(<InquiryAdminPage />);

    await userEvent.click(await screen.findByTestId('waiting-1'));
    await userEvent.click(await screen.findByRole('button', { name: '답변 삭제' }));

    expect(await screen.findByTestId('detail-status')).toHaveTextContent('답변 대기');
    await waitFor(() => expect(screen.queryByTestId('answer-100')).not.toBeInTheDocument());
    await waitFor(() => expect(mocked.listWaiting).toHaveBeenCalledTimes(2));
  });

  it('대기열을 못 읽으면 사유를 남긴다 — 빈 대기열로 보이면 다 답한 줄 안다', async () => {
    mocked.listWaiting.mockRejectedValue(new Error('boom'));
    render(<InquiryAdminPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
