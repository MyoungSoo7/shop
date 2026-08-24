import { describe, it, expect, vi, beforeEach } from 'vitest';
import { paymentApi } from '@/api/payment';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
}));

const payment = { id: 10, orderId: 5, amount: 12000, status: 'PENDING' };

describe('paymentApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('결제를 생성한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: payment });

    const result = await paymentApi.createPayment({ orderId: 5, amount: 12000 } as never);

    expect(api.post).toHaveBeenCalledWith('/payments', { orderId: 5, amount: 12000 });
    expect(result.id).toBe(10);
  });

  it('결제를 승인한다(Authorization)', async () => {
    vi.mocked(api.patch).mockResolvedValueOnce({ data: { ...payment, status: 'AUTHORIZED' } });

    const result = await paymentApi.authorizePayment(10);

    expect(api.patch).toHaveBeenCalledWith('/payments/10/authorize');
    expect(result.status).toBe('AUTHORIZED');
  });

  it('결제를 확정한다(Capture)', async () => {
    vi.mocked(api.patch).mockResolvedValueOnce({ data: { ...payment, status: 'CAPTURED' } });

    const result = await paymentApi.capturePayment(10);

    expect(api.patch).toHaveBeenCalledWith('/payments/10/capture');
    expect(result.status).toBe('CAPTURED');
  });

  it('결제를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: payment });

    const result = await paymentApi.getPayment(10);

    expect(api.get).toHaveBeenCalledWith('/payments/10');
    expect(result.orderId).toBe(5);
  });

  it('토스 결제를 확인한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...payment, status: 'CAPTURED' } });

    const request = { paymentKey: 'pk_1', orderId: 'ORD-1', amount: 12000 };
    const result = await paymentApi.confirmTossPayment(request as never);

    expect(api.post).toHaveBeenCalledWith('/payments/toss/confirm', request);
    expect(result.status).toBe('CAPTURED');
  });

  it('토스 장바구니 일괄 결제를 확인하면 결제 배열을 돌려받는다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: [payment, { ...payment, id: 11 }] });

    const request = { paymentKey: 'pk_2', orderId: 'CART-1', amount: 24000 };
    const result = await paymentApi.confirmTossCartPayment(request as never);

    expect(api.post).toHaveBeenCalledWith('/payments/toss/cart/confirm', request);
    expect(result).toHaveLength(2);
  });

  it('승인 실패는 호출부로 전파한다', async () => {
    vi.mocked(api.patch).mockRejectedValueOnce({ response: { status: 409 } });

    await expect(paymentApi.authorizePayment(10)).rejects.toMatchObject({
      response: { status: 409 },
    });
  });
});
