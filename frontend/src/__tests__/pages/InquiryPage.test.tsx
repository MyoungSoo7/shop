import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Inquiry } from '@/api/inquiry';

/**
 * 내 문의 화면이 지켜야 하는 규율.
 *
 * <p>① <b>사용자 식별자를 보내지 않는다.</b> 목록도 등록도 서버가 토큰에서 주체를 꺼낸다.
 *
 * <p>② <b>상태는 서버가 준 값을 그대로 믿는다.</b> 답변 유무에서 계산되어 오므로, 답변이 지워지면
 * 같은 순간 목록도 '답변 대기'로 돌아온다. 화면이 따로 판정하면 두 곳의 규칙이 갈라진다.
 *
 * <p>③ <b>답변이 달리면 수정·철회 버튼을 내린다.</b> 서버도 409 로 막지만, 누를 수 있는 버튼을
 * 두고 눌렀을 때 실패시키는 것은 화면이 규칙을 모르는 것과 같다.
 *
 * <p>④ <b>상품 문의는 여기서 만들지 않는다.</b> 어느 상품인지는 상품 화면에서만 분명하다.
 */

vi.mock('@/api/inquiry', () => ({
  inquiryApi: { listMine: vi.fn(), ask: vi.fn(), edit: vi.fn(), withdraw: vi.fn() },
}));

const { inquiryApi } = await import('@/api/inquiry');
const { default: InquiryPage } = await import('@/pages/InquiryPage');
const mocked = vi.mocked(inquiryApi);

const base: Inquiry = {
  id: 1,
  userId: 7,
  type: 'GENERAL',
  typeLabel: '1:1 문의',
  productId: null,
  orderId: null,
  subject: '배송 언제 오나요',
  content: '주문한 지 사흘 됐습니다',
  secret: false,
  readable: true,
  status: 'WAITING',
  statusLabel: '답변 대기',
  askedAt: '2026-08-20T10:00:00',
  answers: [],
};

const answered: Inquiry = {
  ...base,
  id: 2,
  status: 'ANSWERED',
  statusLabel: '답변 완료',
  answers: [{ id: 100, answeredBy: 3, content: '내일 도착합니다', answeredAt: '2026-08-21T09:00:00' }],
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.listMine.mockResolvedValue([]);
});

describe('InquiryPage', () => {
  it('첫 화면은 종류를 거르지 않는다 — 전체 탭이 빈 필터를 보내지 않는다', async () => {
    render(<InquiryPage />);

    await waitFor(() => expect(mocked.listMine).toHaveBeenCalledWith(undefined));
  });

  it('탭을 고르면 그 종류만 다시 읽는다', async () => {
    render(<InquiryPage />);
    await waitFor(() => expect(mocked.listMine).toHaveBeenCalledTimes(1));

    await userEvent.click(await screen.findByRole('button', { name: '주문 문의' }));

    await waitFor(() => expect(mocked.listMine).toHaveBeenLastCalledWith('ORDER'));
  });

  it('등록한 문의가 없으면 비어 있다고 말한다', async () => {
    render(<InquiryPage />);

    expect(await screen.findByTestId('inquiries-empty')).toBeInTheDocument();
  });

  /** 어느 상품인지는 상품 화면에서만 분명하다 — 여기서 고르게 하면 상품 번호를 손으로 적게 된다. */
  it('등록 폼에 상품 문의는 없다', async () => {
    render(<InquiryPage />);
    await userEvent.click(await screen.findByRole('button', { name: '문의하기' }));

    const select = await screen.findByLabelText('종류');
    expect(select).toHaveValue('GENERAL');
    expect(screen.queryByRole('option', { name: '상품 문의' })).not.toBeInTheDocument();
  });

  it('1:1 문의는 주문 번호를 묻지 않고, 주문 문의로 바꾸면 묻는다', async () => {
    render(<InquiryPage />);
    await userEvent.click(await screen.findByRole('button', { name: '문의하기' }));

    expect(screen.queryByLabelText('주문 번호')).not.toBeInTheDocument();

    await userEvent.selectOptions(await screen.findByLabelText('종류'), 'ORDER');

    expect(await screen.findByLabelText('주문 번호')).toBeInTheDocument();
  });

  it('등록은 작성자를 보내지 않고, 1:1 문의면 주문 번호도 붙이지 않는다', async () => {
    mocked.ask.mockResolvedValue(base);
    render(<InquiryPage />);
    await userEvent.click(await screen.findByRole('button', { name: '문의하기' }));

    await userEvent.type(await screen.findByLabelText('제목'), '배송 언제 오나요');
    await userEvent.type(screen.getByLabelText('내용'), '주문한 지 사흘 됐습니다');
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() =>
      expect(mocked.ask).toHaveBeenCalledWith({
        type: 'GENERAL',
        orderId: null,
        subject: '배송 언제 오나요',
        content: '주문한 지 사흘 됐습니다',
        secret: false,
      }),
    );
    const [body] = mocked.ask.mock.calls[0];
    expect(body).not.toHaveProperty('userId');
  });

  it('주문 문의는 적은 주문 번호를 숫자로 보낸다', async () => {
    mocked.ask.mockResolvedValue(base);
    render(<InquiryPage />);
    await userEvent.click(await screen.findByRole('button', { name: '문의하기' }));

    await userEvent.selectOptions(await screen.findByLabelText('종류'), 'ORDER');
    await userEvent.type(await screen.findByLabelText('주문 번호'), '55');
    await userEvent.type(screen.getByLabelText('제목'), '주문 문의');
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() =>
      expect(mocked.ask).toHaveBeenCalledWith(expect.objectContaining({ type: 'ORDER', orderId: 55 })),
    );
  });

  it('등록이 실패하면 사유를 남기고 폼을 닫지 않는다', async () => {
    mocked.ask.mockRejectedValue(new Error('boom'));
    render(<InquiryPage />);
    await userEvent.click(await screen.findByRole('button', { name: '문의하기' }));
    await userEvent.type(await screen.findByLabelText('제목'), '질문');
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByTestId('ask-form')).toBeInTheDocument();
  });

  it('답변 전이면 수정·철회를 할 수 있다', async () => {
    mocked.listMine.mockResolvedValue([base]);
    render(<InquiryPage />);

    expect(await screen.findByRole('button', { name: '수정' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '철회' })).toBeInTheDocument();
  });

  it('답변이 달린 문의는 버튼 자체를 내린다', async () => {
    mocked.listMine.mockResolvedValue([answered]);
    render(<InquiryPage />);

    expect(await screen.findByTestId('answer-100')).toBeInTheDocument();
    expect(screen.getByTestId('status-2')).toHaveTextContent('답변 완료');
    expect(screen.queryByRole('button', { name: '수정' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '철회' })).not.toBeInTheDocument();
  });

  it('수정 폼은 지금 값으로 채워지고, 저장하면 바뀔 수 있는 것만 보낸다', async () => {
    mocked.listMine.mockResolvedValue([base]);
    mocked.edit.mockResolvedValue(base);
    render(<InquiryPage />);

    await userEvent.click(await screen.findByRole('button', { name: '수정' }));

    const subject = await screen.findByLabelText('제목 수정');
    expect(subject).toHaveValue('배송 언제 오나요');

    await userEvent.clear(subject);
    await userEvent.type(subject, '고친 제목');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mocked.edit).toHaveBeenCalledWith(1, {
        subject: '고친 제목',
        content: '주문한 지 사흘 됐습니다',
        secret: false,
      }),
    );
  });

  it('저장하면 목록을 다시 읽고 수정 폼을 닫는다', async () => {
    mocked.listMine.mockResolvedValue([base]);
    mocked.edit.mockResolvedValue(base);
    render(<InquiryPage />);

    await userEvent.click(await screen.findByRole('button', { name: '수정' }));
    await userEvent.click(await screen.findByRole('button', { name: '저장' }));

    await waitFor(() => expect(mocked.listMine).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByTestId('edit-form-1')).not.toBeInTheDocument());
  });

  it('철회하면 목록을 다시 읽는다', async () => {
    mocked.listMine.mockResolvedValue([base]);
    mocked.withdraw.mockResolvedValue(undefined);
    render(<InquiryPage />);

    await userEvent.click(await screen.findByRole('button', { name: '철회' }));

    expect(mocked.withdraw).toHaveBeenCalledWith(1);
    await waitFor(() => expect(mocked.listMine).toHaveBeenCalledTimes(2));
  });
});
