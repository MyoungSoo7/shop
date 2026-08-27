import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Inquiry } from '@/api/inquiry';

/**
 * 상품 문의 블록이 지켜야 하는 규율.
 *
 * <p>① <b>가림은 서버가 한다.</b> 못 읽는 문의도 목록에서 줄은 남고 {@code readable:false} 로 온다.
 * 화면이 다시 걸러 내면 문의 개수가 보는 사람마다 달라지고, 비밀글을 쓴 본인조차 자기 질문이
 * 등록됐는지 확인할 수 없다.
 *
 * <p>② <b>작성자를 보내지 않는다.</b> 원본(ssg-front)은 USERID 를 폼으로 받아 그대로 저장했다.
 *
 * <p>③ <b>답변이 달리면 철회 버튼 자체를 내린다.</b> 서버도 409 로 막지만, 누를 수 있는 버튼을
 * 두고 눌렀을 때 실패시키는 것은 화면이 규칙을 모르는 것과 같다.
 */

const mockAuth = { user: null, userId: 7 as number | null, loading: false, refresh: vi.fn() };
vi.mock('@/contexts/useAuth', () => ({ useAuth: () => mockAuth }));

vi.mock('@/api/inquiry', () => ({
  inquiryApi: { listForProduct: vi.fn(), ask: vi.fn(), withdraw: vi.fn() },
}));

const { inquiryApi } = await import('@/api/inquiry');
const { default: ProductInquiries } = await import('@/components/product/ProductInquiries');
const mocked = vi.mocked(inquiryApi);

const base: Inquiry = {
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
  askedAt: '2026-08-20T10:00:00',
  answers: [],
};

const openBlock = async () => {
  await userEvent.click(await screen.findByRole('button', { name: /상품 문의/ }));
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth.userId = 7;
  mocked.listForProduct.mockResolvedValue([]);
});

describe('ProductInquiries', () => {
  it('접힌 채로도 개수를 말할 수 있게 마운트에서 불러온다', async () => {
    mocked.listForProduct.mockResolvedValue([base, { ...base, id: 2 }]);

    render(<ProductInquiries productId={10} />);

    await waitFor(() => expect(mocked.listForProduct).toHaveBeenCalledWith(10));
    expect(await screen.findByRole('button', { name: /상품 문의 \(2개\)/ })).toBeInTheDocument();
  });

  it('아직 문의가 없으면 비어 있다고 말한다 — 빈 목록과 못 불러온 것은 다르다', async () => {
    render(<ProductInquiries productId={10} />);

    await openBlock();

    expect(await screen.findByTestId('inquiries-empty')).toBeInTheDocument();
  });

  it('못 읽는 문의도 줄은 남는다 — 개수가 보는 사람마다 달라지지 않는다', async () => {
    mocked.listForProduct.mockResolvedValue([
      { ...base, id: 3, userId: 99, secret: true, readable: false, subject: '비밀글입니다', content: '' },
    ]);

    render(<ProductInquiries productId={10} />);
    await openBlock();

    expect(await screen.findByTestId('inquiry-3')).toBeInTheDocument();
    expect(screen.getByLabelText('비밀글')).toBeInTheDocument();
  });

  it('등록은 종류·상품만 붙이고 작성자는 보내지 않는다', async () => {
    mocked.ask.mockResolvedValue(base);

    render(<ProductInquiries productId={10} />);
    await openBlock();
    await userEvent.click(await screen.findByRole('button', { name: '문의하기' }));

    await userEvent.type(await screen.findByLabelText('제목'), '정사이즈인가요');
    await userEvent.type(screen.getByLabelText('내용'), '평소 270 신습니다');
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() =>
      expect(mocked.ask).toHaveBeenCalledWith({
        type: 'PRODUCT',
        productId: 10,
        subject: '정사이즈인가요',
        content: '평소 270 신습니다',
        secret: false,
      }),
    );
  });

  it('등록한 뒤 목록을 다시 읽는다 — 방금 쓴 문의가 없는 화면으로 남지 않는다', async () => {
    mocked.ask.mockResolvedValue(base);

    render(<ProductInquiries productId={10} />);
    await openBlock();
    await userEvent.click(await screen.findByRole('button', { name: '문의하기' }));
    await userEvent.type(await screen.findByLabelText('제목'), '질문');
    await userEvent.type(screen.getByLabelText('내용'), '본문');
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(mocked.listForProduct).toHaveBeenCalledTimes(2));
  });

  it('등록이 실패하면 사유를 남기고 폼을 닫지 않는다', async () => {
    mocked.ask.mockRejectedValue(new Error('boom'));

    render(<ProductInquiries productId={10} />);
    await openBlock();
    await userEvent.click(await screen.findByRole('button', { name: '문의하기' }));
    await userEvent.type(await screen.findByLabelText('제목'), '질문');
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByTestId('inquiry-form')).toBeInTheDocument();
  });

  it('철회는 내 문의이고 답변 전일 때만 보인다', async () => {
    mocked.listForProduct.mockResolvedValue([base]);

    render(<ProductInquiries productId={10} />);
    await openBlock();

    expect(await screen.findByRole('button', { name: '철회' })).toBeInTheDocument();
  });

  it('남의 문의에는 철회 버튼이 없다', async () => {
    mocked.listForProduct.mockResolvedValue([{ ...base, userId: 99 }]);

    render(<ProductInquiries productId={10} />);
    await openBlock();

    expect(await screen.findByTestId('inquiry-1')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '철회' })).not.toBeInTheDocument();
  });

  it('답변이 달린 내 문의도 철회 버튼을 내린다 — 서버는 409 로 막는다', async () => {
    mocked.listForProduct.mockResolvedValue([
      {
        ...base,
        status: 'ANSWERED',
        statusLabel: '답변 완료',
        answers: [{ id: 100, answeredBy: 3, content: '정사이즈입니다', answeredAt: '2026-08-21T09:00:00' }],
      },
    ]);

    render(<ProductInquiries productId={10} />);
    await openBlock();

    expect(await screen.findByTestId('answer-100')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '철회' })).not.toBeInTheDocument();
  });

  it('철회하면 목록을 다시 읽는다', async () => {
    mocked.listForProduct.mockResolvedValue([base]);
    mocked.withdraw.mockResolvedValue(undefined);

    render(<ProductInquiries productId={10} />);
    await openBlock();
    await userEvent.click(await screen.findByRole('button', { name: '철회' }));

    expect(mocked.withdraw).toHaveBeenCalledWith(1);
    await waitFor(() => expect(mocked.listForProduct).toHaveBeenCalledTimes(2));
  });
});
