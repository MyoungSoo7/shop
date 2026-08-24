import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CashReceiptPanel from '@/components/CashReceiptPanel';
import { cashReceiptApi, type CashReceipt } from '@/api/cashReceipt';

vi.mock('@/api/cashReceipt', () => ({
  cashReceiptApi: { getByOrder: vi.fn(), issueForOrder: vi.fn() },
}));

const mocked = vi.mocked(cashReceiptApi);

const receipt = (overrides: Partial<CashReceipt> = {}): CashReceipt => ({
  id: 1,
  paymentId: 10,
  orderId: 77,
  purpose: 'INCOME_DEDUCTION',
  purposeLabel: '소득공제',
  identifierType: 'MOBILE',
  maskedIdentifier: '010-****-5678',
  totalAmount: 11000,
  supplyAmount: 10000,
  vatAmount: 1000,
  status: 'ISSUED',
  approvalNumber: 'A-2026-0001',
  failureReason: null,
  issuedAt: '2026-08-20T10:00:00',
  ...overrides,
});

const httpError = (status: number, message?: string) => ({
  response: { status, data: message ? { message } : null },
});

const openPanel = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByRole('button', { name: /현금영수증/ }));
};

describe('CashReceiptPanel — 접힘/펼침', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.getByOrder.mockResolvedValue(null);
  });

  it('처음에는 접혀 있고 서버를 부르지 않는다 — 주문마다 조회를 날리면 목록이 느려진다', async () => {
    render(<CashReceiptPanel orderId={77} />);

    expect(await screen.findByRole('button', { name: /현금영수증/ })).toBeInTheDocument();
    expect(mocked.getByOrder).not.toHaveBeenCalled();
  });

  it('펼칠 때 그 주문의 발급 이력을 읽는다', async () => {
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);

    await openPanel(user);

    await waitFor(() => expect(mocked.getByOrder).toHaveBeenCalledWith(77));
  });
});

describe('CashReceiptPanel — 이미 발급된 경우', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('상태·용도·마스킹된 식별번호와 공급가액/부가세를 보여 준다', async () => {
    mocked.getByOrder.mockResolvedValue(receipt());
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);

    await openPanel(user);

    expect(await screen.findByText('발급 완료')).toBeInTheDocument();
    expect(screen.getByText('소득공제')).toBeInTheDocument();
    expect(screen.getByText('010-****-5678')).toBeInTheDocument();
    expect(screen.getByText('10,000원 / 1,000원')).toBeInTheDocument();
    expect(screen.getByText('A-2026-0001')).toBeInTheDocument();
  });

  it('식별번호는 마스킹된 값만 나온다 — 원문은 서버가 아예 내려주지 않는다', async () => {
    mocked.getByOrder.mockResolvedValue(receipt());
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);

    await openPanel(user);
    await screen.findByText('010-****-5678');

    expect(screen.queryByText(/01012345678|010-1234-5678/)).not.toBeInTheDocument();
  });

  it('발급에 실패한 이력이면 사유를 그대로 남긴다', async () => {
    mocked.getByOrder.mockResolvedValue(receipt({
      status: 'FAILED', approvalNumber: null, failureReason: '국세청 응답 없음',
    }));
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);

    await openPanel(user);

    expect(await screen.findByText('발급 실패')).toBeInTheDocument();
    expect(screen.getByText('사유: 국세청 응답 없음')).toBeInTheDocument();
  });

  it('이력이 있으면 발급 폼을 다시 띄우지 않는다 — 중복 발급 경로를 열지 않는다', async () => {
    mocked.getByOrder.mockResolvedValue(receipt());
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);

    await openPanel(user);
    await screen.findByText('발급 완료');

    expect(screen.queryByRole('button', { name: '발급 신청' })).not.toBeInTheDocument();
  });
});

describe('CashReceiptPanel — 용도에 따라 식별번호가 갈린다', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.getByOrder.mockResolvedValue(null);
  });

  it('소득공제는 휴대폰번호·현금영수증카드만 고를 수 있다', async () => {
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);
    await openPanel(user);

    const select = await screen.findByRole('combobox');
    expect(select).toHaveValue('MOBILE');
    expect(within(select).getAllByRole('option').map(o => o.textContent))
      .toEqual(['휴대폰번호', '현금영수증카드']);
  });

  it('지출증빙으로 바꾸면 사업자등록번호만 남고 입력값이 지워진다', async () => {
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);
    await openPanel(user);

    await user.type(await screen.findByRole('textbox'), '010-1234-5678');
    await user.click(screen.getByRole('button', { name: '지출증빙 (사업자)' }));

    expect(await screen.findByRole('combobox')).toHaveValue('BUSINESS_NUMBER');
    expect(screen.getByRole('textbox')).toHaveValue('');
    expect(screen.getByRole('textbox')).toHaveAttribute('placeholder', '220-81-62517');
  });

  it('현금영수증카드를 고르면 안내 문구도 카드 자릿수로 바뀐다', async () => {
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);
    await openPanel(user);

    await user.selectOptions(await screen.findByRole('combobox'), 'CASH_RECEIPT_CARD');

    expect(screen.getByRole('combobox')).toHaveValue('CASH_RECEIPT_CARD');
    expect(screen.getByRole('textbox'))
      .toHaveAttribute('placeholder', '카드 뒷면 13~19자리');
  });

  it('주민등록번호는 아예 선택지에 없다 — 저장하지 않은 데이터는 새지 않는다', async () => {
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);
    await openPanel(user);

    await screen.findByRole('combobox');
    expect(screen.queryByRole('option', { name: /주민등록번호/ })).not.toBeInTheDocument();
  });
});

describe('CashReceiptPanel — 발급 신청', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.getByOrder.mockResolvedValue(null);
  });

  it('식별번호가 비어 있으면 신청 버튼이 잠긴다', async () => {
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);
    await openPanel(user);

    expect(await screen.findByRole('button', { name: '발급 신청' })).toBeDisabled();

    await user.type(screen.getByRole('textbox'), '   ');
    expect(screen.getByRole('button', { name: '발급 신청' })).toBeDisabled();
  });

  it('고른 용도·식별번호를 그대로 실어 보낸다', async () => {
    mocked.issueForOrder.mockResolvedValue(receipt({ status: 'REQUESTED', approvalNumber: null }));
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);
    await openPanel(user);

    await user.type(await screen.findByRole('textbox'), '010-1234-5678');
    await user.click(screen.getByRole('button', { name: '발급 신청' }));

    await waitFor(() => expect(mocked.issueForOrder).toHaveBeenCalledWith(77, {
      purpose: 'INCOME_DEDUCTION',
      identifierType: 'MOBILE',
      identifierValue: '010-1234-5678',
    }));
    expect(await screen.findByText('발급 요청 중')).toBeInTheDocument();
  });

  it('발급 실패는 서버가 준 사유를 그대로 보여 준다', async () => {
    mocked.issueForOrder.mockRejectedValue(
      httpError(400, '카드 결제 주문은 현금영수증 대상이 아닙니다'));
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);
    await openPanel(user);

    await user.type(await screen.findByRole('textbox'), '010-1234-5678');
    await user.click(screen.getByRole('button', { name: '발급 신청' }));

    expect(await screen.findByText('카드 결제 주문은 현금영수증 대상이 아닙니다')).toBeInTheDocument();
  });
});

describe('CashReceiptPanel — 조회 실패의 결', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('404 는 조용히 넘긴다 — "발급 이력 없음"은 오류가 아니다', async () => {
    mocked.getByOrder.mockRejectedValue(httpError(404));
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);

    await openPanel(user);

    expect(await screen.findByRole('button', { name: '발급 신청' })).toBeInTheDocument();
    expect(screen.queryByText(/실패했습니다/)).not.toBeInTheDocument();
  });

  it('그 밖의 오류는 드러낸다 — 조용히 삼키면 "대상 아님"과 장애가 같아 보인다', async () => {
    mocked.getByOrder.mockRejectedValue(httpError(500, '일시적인 오류입니다'));
    const user = userEvent.setup();
    render(<CashReceiptPanel orderId={77} />);

    await openPanel(user);

    expect(await screen.findByText('일시적인 오류입니다')).toBeInTheDocument();
  });
});
