import { describe, it, expect, vi, beforeEach } from 'vitest';
import { couponApi } from '@/api/coupon';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('couponApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('쿠폰 유효성은 사용자·주문금액과 함께 검증한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { valid: true, discountAmount: 3000, message: null },
    });

    const result = await couponApi.validate('WELCOME10', 7, 30000);

    expect(api.get).toHaveBeenCalledWith('/coupons/WELCOME10/validate', {
      params: { userId: 7, amount: 30000 },
    });
    expect(result.discountAmount).toBe(3000);
  });

  it('최소 주문금액 미달 등 무효 사유를 그대로 전달한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { valid: false, discountAmount: 0, message: '최소 주문금액 미달' },
    });

    const result = await couponApi.validate('WELCOME10', 7, 1000);

    expect(result.valid).toBe(false);
    expect(result.message).toBe('최소 주문금액 미달');
  });

  it('미리보기는 장바구니 라인을 그대로 주문 경로로 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: {
        valid: true,
        message: '',
        subtotal: 100000,
        discountAmount: 1000,
        eligibleAmount: 10000,
        finalAmount: 99000,
      },
    });

    const lines = [
      { productId: 100, quantity: 1 },
      { productId: 999, quantity: 1 },
    ];
    const result = await couponApi.preview(7, 'P10', lines);

    expect(api.post).toHaveBeenCalledWith('/orders/coupon-preview', {
      userId: 7,
      lines,
      couponCode: 'P10',
    });
    // 상품 전용 쿠폰이면 할인이 걸린 금액(eligibleAmount)이 소계보다 작다.
    expect(result.eligibleAmount).toBe(10000);
    expect(result.discountAmount).toBe(1000);
  });

  it('쿠폰 사용을 기록한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: undefined });

    await couponApi.use('WELCOME10', 7, 100);

    expect(api.post).toHaveBeenCalledWith('/coupons/WELCOME10/use', { userId: 7, orderId: 100 });
  });

  it('전체 쿠폰 목록을 조회한다(관리자)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [{ id: 1, code: 'WELCOME10' }] });

    const result = await couponApi.getAll();

    expect(api.get).toHaveBeenCalledWith('/coupons');
    expect(result).toHaveLength(1);
  });

  it('쿠폰을 생성한다(관리자)', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { id: 2, code: 'SUMMER' } });

    const result = await couponApi.create({ code: 'SUMMER', discountRate: 10 } as never);

    expect(api.post).toHaveBeenCalledWith('/coupons', { code: 'SUMMER', discountRate: 10 });
    expect(result.id).toBe(2);
  });

  it('이미 사용한 쿠폰은 409 가 전파된다', async () => {
    vi.mocked(api.post).mockRejectedValueOnce({ response: { status: 409 } });

    await expect(couponApi.use('WELCOME10', 7, 100)).rejects.toMatchObject({
      response: { status: 409 },
    });
  });
});
