import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CouponInput from '@/components/coupon/CouponInput';
import { couponApi } from '@/api/coupon';

vi.mock('@/api/coupon', () => ({
  couponApi: {
    validate: vi.fn(),
  },
}));

const props = {
  userId: 7,
  orderAmount: 30000,
  onApply: vi.fn(),
  onRemove: vi.fn(),
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('CouponInput — 적용 전', () => {
  it('코드가 비어 있으면 적용 버튼이 잠겨 있다', () => {
    render(<CouponInput {...props} />);

    expect(screen.getByRole('button', { name: '적용' })).toBeDisabled();
  });

  it('입력값은 대문자로 정규화된다', async () => {
    render(<CouponInput {...props} />);

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');

    expect(screen.getByPlaceholderText(/쿠폰 코드 입력/)).toHaveValue('WELCOME10');
  });

  it('유효한 쿠폰이면 onApply 를 호출하고 입력을 비운다', async () => {
    vi.mocked(couponApi.validate).mockResolvedValueOnce({
      valid: true,
      discountAmount: 3000,
      message: null,
    } as never);
    render(<CouponInput {...props} />);

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));

    await waitFor(() => expect(props.onApply).toHaveBeenCalled());
    expect(couponApi.validate).toHaveBeenCalledWith('WELCOME10', 7, 30000);
    expect(props.onApply).toHaveBeenCalledWith(
      expect.objectContaining({ discountAmount: 3000 }),
      'WELCOME10',
    );
    expect(screen.getByPlaceholderText(/쿠폰 코드 입력/)).toHaveValue('');
  });

  it('무효한 쿠폰이면 서버 사유를 보여 준다', async () => {
    vi.mocked(couponApi.validate).mockResolvedValueOnce({
      valid: false,
      discountAmount: 0,
      message: '최소 주문금액 미달',
    } as never);
    render(<CouponInput {...props} />);

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));

    expect(await screen.findByText('최소 주문금액 미달')).toBeInTheDocument();
    expect(props.onApply).not.toHaveBeenCalled();
  });

  it('조회가 실패하면 일반 오류 문구를 보여 준다', async () => {
    vi.mocked(couponApi.validate).mockRejectedValueOnce(new Error('network'));
    render(<CouponInput {...props} />);

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'x1');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));

    expect(await screen.findByText('쿠폰 확인 중 오류가 발생했습니다.')).toBeInTheDocument();
  });

  it('Enter 로도 적용된다', async () => {
    vi.mocked(couponApi.validate).mockResolvedValueOnce({
      valid: true,
      discountAmount: 1000,
      message: null,
    } as never);
    render(<CouponInput {...props} />);

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10{Enter}');

    await waitFor(() => expect(couponApi.validate).toHaveBeenCalled());
  });

  it('다시 입력하면 직전 오류 문구가 사라진다', async () => {
    vi.mocked(couponApi.validate).mockResolvedValueOnce({
      valid: false,
      discountAmount: 0,
      message: '만료된 쿠폰',
    } as never);
    render(<CouponInput {...props} />);

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'old');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));
    expect(await screen.findByText('만료된 쿠폰')).toBeInTheDocument();

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'n');

    expect(screen.queryByText('만료된 쿠폰')).not.toBeInTheDocument();
  });
});

describe('CouponInput — 적용 후', () => {
  it('적용된 코드를 보여 주고 취소를 누르면 onRemove 를 부른다', async () => {
    render(<CouponInput {...props} appliedCode="WELCOME10" />);

    expect(screen.getByText('WELCOME10')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/쿠폰 코드 입력/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(props.onRemove).toHaveBeenCalledTimes(1);
  });
});
