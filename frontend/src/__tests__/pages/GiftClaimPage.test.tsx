import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import GiftClaimPage from '@/pages/GiftClaimPage';
import { giftClaimApi, type GiftViewResponse } from '@/api/gift';

vi.mock('@/api/gift', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/gift')>()),
  giftClaimApi: {
    view: vi.fn(),
    requestCode: vi.fn(),
    verify: vi.fn(),
    submitAddress: vi.fn(),
  },
}));

const view = (over: Partial<GiftViewResponse> = {}): GiftViewResponse => ({
  orderId: 42,
  status: 'PENDING',
  actionable: true,
  recipientName: '김수령',
  maskedPhone: '010-****-5678',
  message: '생일 축하해',
  expiresAt: '2026-09-10T10:00:00',
  items: [{ productName: '머그컵', quantity: 2 }],
  ...over,
});

const renderPage = (token = 'tok-abc') =>
  render(
    <MemoryRouter initialEntries={[`/gift/${token}`]}>
      <Routes>
        <Route path="/gift/:token" element={<GiftClaimPage />} />
      </Routes>
    </MemoryRouter>,
  );

describe('GiftClaimPage — 받는 사람이 보는 화면', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('링크에 담긴 토큰으로 바로 읽는다 — 로그인을 요구하지 않는다', async () => {
    vi.mocked(giftClaimApi.view).mockResolvedValue(view());
    renderPage();
    await waitFor(() => expect(giftClaimApi.view).toHaveBeenCalledWith('tok-abc'));
    expect(await screen.findByText(/김수령 님께/)).toBeInTheDocument();
    expect(screen.getByText('생일 축하해')).toBeInTheDocument();
    expect(screen.getByText('머그컵')).toBeInTheDocument();
  });

  it('없는 링크는 "찾을 수 없다"로 끝난다 — 폼을 띄우지 않는다', async () => {
    vi.mocked(giftClaimApi.view).mockRejectedValue(new Error('404'));
    renderPage();
    expect(await screen.findByText(/선물을 찾을 수 없습니다/)).toBeInTheDocument();
    expect(screen.queryByText('인증번호 받기')).not.toBeInTheDocument();
  });

  it('링크만으로는 주소 입력이 열리지 않는다 — 링크를 주운 사람이 배송지를 돌리는 길', async () => {
    vi.mocked(giftClaimApi.view).mockResolvedValue(view());
    renderPage();
    expect(await screen.findByText('인증번호 받기')).toBeInTheDocument();
    expect(screen.queryByLabelText('우편번호')).not.toBeInTheDocument();
  });

  it('인증번호를 받으면 어디로 갔는지 가려진 번호로 알려 준다', async () => {
    vi.mocked(giftClaimApi.view).mockResolvedValue(view());
    vi.mocked(giftClaimApi.requestCode).mockResolvedValue(undefined);
    renderPage();

    fireEvent.click(await screen.findByText('인증번호 받기'));

    await waitFor(() => expect(giftClaimApi.requestCode).toHaveBeenCalledWith('tok-abc'));
    expect(await screen.findByText(/010-\*\*\*\*-5678 로 인증번호를 보냈습니다/)).toBeInTheDocument();
    expect(screen.getByLabelText('인증번호 6자리')).toBeInTheDocument();
  });

  it('6자리가 아니면 보낼 수 없고, 숫자가 아닌 입력은 걸러진다', async () => {
    vi.mocked(giftClaimApi.view).mockResolvedValue(view());
    vi.mocked(giftClaimApi.requestCode).mockResolvedValue(undefined);
    renderPage();
    fireEvent.click(await screen.findByText('인증번호 받기'));

    const input = await screen.findByLabelText('인증번호 6자리');
    fireEvent.change(input, { target: { value: '12a3' } });
    expect((input as HTMLInputElement).value).toBe('123');
    expect(screen.getByText('확인').closest('button')).toBeDisabled();

    fireEvent.change(input, { target: { value: '1234567' } });
    expect((input as HTMLInputElement).value).toBe('123456'); // 6자리에서 잘린다
    expect(screen.getByText('확인').closest('button')).not.toBeDisabled();
  });

  it('틀린 번호의 서버 메시지를 그대로 보여 준다 — 몇 번 남았는지 모르면 문의로 온다', async () => {
    vi.mocked(giftClaimApi.view).mockResolvedValue(view());
    vi.mocked(giftClaimApi.requestCode).mockResolvedValue(undefined);
    vi.mocked(giftClaimApi.verify).mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: '인증번호가 맞지 않습니다. 남은 시도 4회' } },
    });
    renderPage();
    fireEvent.click(await screen.findByText('인증번호 받기'));
    fireEvent.change(await screen.findByLabelText('인증번호 6자리'), { target: { value: '000000' } });
    fireEvent.click(screen.getByText('확인'));

    expect(await screen.findByText(/남은 시도 4회/)).toBeInTheDocument();
    // 실패했는데 주소 폼이 열리면 인증이 장식이 된다.
    expect(screen.queryByLabelText('우편번호')).not.toBeInTheDocument();
  });

  it('본인확인을 통과해야 주소 폼이 열리고, 낸 값이 그대로 서버로 간다', async () => {
    vi.mocked(giftClaimApi.view)
      .mockResolvedValueOnce(view())
      .mockResolvedValue(view({ status: 'CLAIMED', actionable: false }));
    vi.mocked(giftClaimApi.requestCode).mockResolvedValue(undefined);
    vi.mocked(giftClaimApi.verify).mockResolvedValue(undefined);
    vi.mocked(giftClaimApi.submitAddress).mockResolvedValue(undefined);
    renderPage();

    fireEvent.click(await screen.findByText('인증번호 받기'));
    fireEvent.change(await screen.findByLabelText('인증번호 6자리'), { target: { value: '123456' } });
    fireEvent.click(screen.getByText('확인'));

    const postal = await screen.findByLabelText('우편번호');
    fireEvent.change(postal, { target: { value: '06236' } });
    fireEvent.change(screen.getByLabelText('주소'), { target: { value: '서울시 강남구 테헤란로 1' } });
    fireEvent.change(screen.getByLabelText('연락처'), { target: { value: '010-1234-5678' } });
    fireEvent.click(screen.getByText('이 주소로 받기'));

    await waitFor(() => expect(giftClaimApi.submitAddress).toHaveBeenCalledTimes(1));
    const [token, payload] = vi.mocked(giftClaimApi.submitAddress).mock.calls[0];
    expect(token).toBe('tok-abc');
    expect(payload.postalCode).toBe('06236');
    expect(payload.address1).toBe('서울시 강남구 테헤란로 1');
    // 이름은 비워 둘 수 있다 — 서버가 선물에 적힌 이름으로 채운다.
    expect(payload.recipientName).toBe('');
  });

  it('이미 받아 간 링크로 다시 들어오면 폼 대신 끝났다고 말한다', async () => {
    vi.mocked(giftClaimApi.view).mockResolvedValue(view({ status: 'CLAIMED', actionable: false }));
    renderPage();
    expect(await screen.findByText(/배송지가 전달됐습니다/)).toBeInTheDocument();
    expect(screen.queryByLabelText('우편번호')).not.toBeInTheDocument();
  });

  it('만료·회수된 링크는 왜 못 받는지 적고 아무 버튼도 내주지 않는다', async () => {
    vi.mocked(giftClaimApi.view).mockResolvedValue(view({ status: 'EXPIRED', actionable: false }));
    renderPage();
    expect(await screen.findByText(/더 이상 받을 수 없습니다/)).toBeInTheDocument();
    expect(screen.getByText(/기간 만료/)).toBeInTheDocument();
    expect(screen.queryByText('인증번호 받기')).not.toBeInTheDocument();
  });

  it('본인확인만 끝낸 채 나갔다 돌아오면 주소 폼부터 시작한다', async () => {
    vi.mocked(giftClaimApi.view).mockResolvedValue(view({ status: 'VERIFIED' }));
    renderPage();
    expect(await screen.findByLabelText('우편번호')).toBeInTheDocument();
    expect(giftClaimApi.requestCode).not.toHaveBeenCalled();
  });
});
