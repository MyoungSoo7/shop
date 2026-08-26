import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import OrderGiftPanel from '@/components/order/OrderGiftPanel';
import { ToastProvider } from '@/contexts/ToastContext';
import { giftApi, type GiftStatusResponse } from '@/api/gift';

// 서버로 나가는 호출만 가짜로 바꾼다. 상태 라벨과 "아직 열려 있는가" 판정은 진짜를 쓴다 —
// 그것까지 가짜로 만들면 화면이 실제로 그 조건을 붙였는지 검사하지 못한다.
vi.mock('@/api/gift', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/gift')>()),
  giftApi: { status: vi.fn(), resend: vi.fn(), cancel: vi.fn(), send: vi.fn() },
}));

const status = (over: Partial<GiftStatusResponse> = {}): GiftStatusResponse => ({
  orderId: 42,
  giftClaimId: 5,
  status: 'PENDING',
  recipientName: '김수령',
  maskedRecipientPhone: '010-****-5678',
  expiresAt: '2026-09-10T10:00:00',
  verifiedAt: null,
  claimedAt: null,
  ...over,
});

const renderPanel = () =>
  render(
    <ToastProvider>
      <OrderGiftPanel orderId={42} />
    </ToastProvider>,
  );

const open = async () => {
  fireEvent.click(screen.getByText(/선물 진행 상황/));
  await waitFor(() => expect(giftApi.status).toHaveBeenCalledWith(42));
};

describe('OrderGiftPanel — 보낸 선물이 어디까지 갔는지', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('접혀 있는 동안에는 서버를 부르지 않는다 — 목록의 대부분은 선물이 아니다', () => {
    renderPanel();
    expect(giftApi.status).not.toHaveBeenCalled();
  });

  it('선물이 아닌 주문(404)은 오류가 아니라 "선물이 아님"으로 읽는다', async () => {
    vi.mocked(giftApi.status).mockRejectedValue(new Error('404'));
    renderPanel();
    await open();
    expect(await screen.findByText('선물로 보낸 주문이 아닙니다.')).toBeInTheDocument();
  });

  it('두 번 펼쳐도 조회는 한 번뿐이다', async () => {
    vi.mocked(giftApi.status).mockResolvedValue(status());
    renderPanel();
    await open();
    fireEvent.click(screen.getByText(/선물 진행 상황/)); // 접기
    fireEvent.click(screen.getByText(/선물 진행 상황/)); // 다시 펼치기
    await waitFor(() => expect(giftApi.status).toHaveBeenCalledTimes(1));
  });

  it('받는 사람은 가려진 번호로만 보인다 — 온전한 번호는 화면에 오지 않는다', async () => {
    vi.mocked(giftApi.status).mockResolvedValue(status());
    renderPanel();
    await open();
    expect(await screen.findByText(/010-\*\*\*\*-5678/)).toBeInTheDocument();
    expect(screen.queryByText(/010-1234-5678/)).not.toBeInTheDocument();
  });

  it('아직 열려 있으면 재발송·회수를 내준다', async () => {
    vi.mocked(giftApi.status).mockResolvedValue(status());
    renderPanel();
    await open();
    expect(await screen.findByText('링크 다시 보내기')).toBeInTheDocument();
    expect(screen.getByText('링크 회수')).toBeInTheDocument();
  });

  it('이미 받아 간 선물에는 재발송·회수가 없다 — 되돌릴 링크가 없다', async () => {
    vi.mocked(giftApi.status).mockResolvedValue(
      status({ status: 'CLAIMED', claimedAt: '2026-08-28T09:00:00', verifiedAt: '2026-08-28T08:59:00' }),
    );
    renderPanel();
    await open();
    expect(await screen.findByText('배송지 입력 완료')).toBeInTheDocument();
    expect(screen.queryByText('링크 다시 보내기')).not.toBeInTheDocument();
    expect(screen.queryByText('링크 회수')).not.toBeInTheDocument();
    // 만료 시각은 이미 받아 간 선물 옆에서는 오해만 부른다.
    expect(screen.queryByText(/만료됩니다/)).not.toBeInTheDocument();
  });

  it('만료된 선물은 왜 닫혔는지 대신 적는다 — 버튼만 사라지면 고장난 줄 안다', async () => {
    vi.mocked(giftApi.status).mockResolvedValue(status({ status: 'EXPIRED' }));
    renderPanel();
    await open();
    expect(await screen.findByText(/기간이 지나 링크가 닫혔습니다/)).toBeInTheDocument();
  });

  it('재발송에 성공하면 상태를 다시 읽는다 — 화면의 만료일이 옛 링크의 것으로 남으면 안 된다', async () => {
    vi.mocked(giftApi.status).mockResolvedValue(status());
    vi.mocked(giftApi.resend).mockResolvedValue({ linkDelivered: true });
    renderPanel();
    await open();

    fireEvent.click(await screen.findByText('링크 다시 보내기'));

    await waitFor(() => expect(giftApi.resend).toHaveBeenCalledWith(42));
    await waitFor(() => expect(giftApi.status).toHaveBeenCalledTimes(2));
    expect(await screen.findByText(/이전 링크는 무효가 됩니다/)).toBeInTheDocument();
  });

  it('발송이 실패하면 성공한 척하지 않는다 — linkDelivered=false 를 무시하면 아무도 모르게 만료된다', async () => {
    vi.mocked(giftApi.status).mockResolvedValue(status());
    vi.mocked(giftApi.resend).mockResolvedValue({ linkDelivered: false });
    renderPanel();
    await open();

    fireEvent.click(await screen.findByText('링크 다시 보내기'));

    expect(await screen.findByText(/링크를 보내지 못했습니다/)).toBeInTheDocument();
  });

  it('회수는 링크만 닫는다 — 결제 취소가 아니라고 말해 준다', async () => {
    vi.mocked(giftApi.status).mockResolvedValue(status());
    vi.mocked(giftApi.cancel).mockResolvedValue(status({ status: 'CANCELED' }));
    renderPanel();
    await open();

    fireEvent.click(await screen.findByText('링크 회수'));

    await waitFor(() => expect(giftApi.cancel).toHaveBeenCalledWith(42));
    expect(await screen.findByText(/결제 취소는 취소·환불 신청에서/)).toBeInTheDocument();
    expect(screen.queryByText('링크 회수')).not.toBeInTheDocument();
  });
});
