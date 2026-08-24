import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GiftCardConsolePage from '@/pages/system/GiftCardConsolePage';
import MyBalancesPage from '@/pages/MyBalancesPage';
import { giftCardApi } from '@/api/giftCard';
import { pointApi } from '@/api/point';

vi.mock('@/api/giftCard', () => ({
  giftCardApi: { issue: vi.fn(), runExpiry: vi.fn(), redeem: vi.fn(), myBalance: vi.fn() },
}));
vi.mock('@/api/point', () => ({
  pointApi: { grant: vi.fn(), runExpiry: vi.fn(), myBalance: vi.fn() },
}));
// 잔액 화면은 예치금도 함께 읽는다. 여기서는 계좌 없는 사용자(=셀러가 아님)를 가정한다 —
// 예치금 구획 자체가 그려지지 않아 이 파일이 보는 포인트·상품권 계약은 그대로다.
// 예치금 3상태는 MyBalancesPage.test.tsx 가 따로 못박는다.
vi.mock('@/api/deposit', () => ({
  depositApi: { myAccount: vi.fn().mockResolvedValue(null), accountOf: vi.fn() },
}));

// 지급 계좌도 같은 이유로 막아 둔다. 안 막으면 조회가 실패해 계좌 구획이 자기 alert 를 띄우고,
// 이 파일이 보는 "등록 실패 문구" alert 와 섞여 어느 쪽을 집는지 모르게 된다.
// 계좌 구획의 규율은 MyBalancesPage.test.tsx 가 따로 못박는다.
vi.mock('@/api/sellerBankAccount', () => ({
  sellerBankAccountApi: {
    mine: vi.fn().mockResolvedValue(null), saveMine: vi.fn(), of: vi.fn(), save: vi.fn(),
  },
}));

const mockedGiftCard = vi.mocked(giftCardApi);
const mockedPoint = vi.mocked(pointApi);

describe('GiftCardConsolePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('발행하면 코드가 화면에 나오고, 저장 전까지 경고가 유지된다 — 코드는 다시 볼 수 없다', async () => {
    mockedGiftCard.issue.mockResolvedValue([
      { giftCardId: 1, code: 'GC-ABCD2345EFGH6789', codeLast4: '6789', faceAmount: 50000 },
    ]);
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    expect(await screen.findByText('GC-ABCD2345EFGH6789')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('다시 볼 수 없습니다');
  });

  it('발행 요청은 화면 입력값을 그대로 보낸다', async () => {
    mockedGiftCard.issue.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    const quantity = screen.getByLabelText('장수');
    await user.clear(quantity);
    await user.type(quantity, '3');
    await user.click(screen.getByLabelText('즉시 활성화'));   // 기본 true → false

    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    await waitFor(() => expect(mockedGiftCard.issue).toHaveBeenCalledWith(
      expect.objectContaining({ quantity: 3, faceAmount: 50000, activate: false })));
  });

  it('미리보기 전에는 소멸 실행이 잠겨 있다', async () => {
    mockedGiftCard.runExpiry.mockResolvedValue({ cardCount: 2, forfeitedTotal: 30000, dryRun: true });
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    expect(screen.getByRole('button', { name: '소멸 실행' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: '미리보기' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeEnabled());
  });
});

describe('MyBalancesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedPoint.myBalance.mockResolvedValue({ userId: 42, available: 3000 });
    mockedGiftCard.myBalance.mockResolvedValue({ userId: 42, available: 50000 });
  });

  it('포인트와 상품권 잔액을 합치지 않고 따로 보여 준다', async () => {
    render(<MyBalancesPage />);

    expect(await screen.findByTestId('point-balance')).toHaveTextContent('3,000P');
    expect(screen.getByTestId('giftcard-balance')).toHaveTextContent('50,000원');
  });

  it('코드를 등록하면 잔액을 다시 불러온다', async () => {
    mockedGiftCard.redeem.mockResolvedValue({
      giftCardId: 1, codeLast4: '6789', faceAmount: 50000, totalBalance: 100000,
    });
    const user = userEvent.setup();
    render(<MyBalancesPage />);
    await screen.findByTestId('point-balance');

    await user.type(screen.getByLabelText('기프트카드 코드'), 'GC-ABCD2345EFGH6789');
    await user.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(mockedGiftCard.redeem).toHaveBeenCalledWith('GC-ABCD2345EFGH6789'));
    // 등록 후 재조회 — 초기 1회 + 등록 후 1회
    await waitFor(() => expect(mockedGiftCard.myBalance).toHaveBeenCalledTimes(2));
  });

  it('등록 실패는 서버 문구를 그대로 보여 준다 — 화면이 사유를 지어내면 코드 존재가 샌다', async () => {
    mockedGiftCard.redeem.mockRejectedValue({
      response: { data: { message: '사용할 수 없는 기프트카드 코드입니다' } },
    });
    const user = userEvent.setup();
    render(<MyBalancesPage />);
    await screen.findByTestId('point-balance');

    await user.type(screen.getByLabelText('기프트카드 코드'), 'GC-0000000000000000');
    await user.click(screen.getByRole('button', { name: '등록' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('사용할 수 없는 기프트카드 코드입니다');
    expect(alert).not.toHaveTextContent('이미 등록');
  });

  it('빈 코드로는 등록 버튼이 눌리지 않는다', async () => {
    render(<MyBalancesPage />);
    await screen.findByTestId('point-balance');

    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled();
  });
});
