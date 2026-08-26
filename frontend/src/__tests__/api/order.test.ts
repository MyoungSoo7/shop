import { describe, it, expect, vi, beforeEach } from 'vitest';
import { orderApi } from '@/api/order';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
}));

const order = { id: 100, userId: 7, status: 'CREATED', totalAmount: 30000 };

/** 결제 화면이 채워 보내는 배송지. 서버가 없으면 400 이라 모든 다건 주문 호출에 붙는다. */
const address = {
  recipientName: '홍길동',
  phone: '010-1234-5678',
  postalCode: '06236',
  address1: '서울시 강남구 테헤란로 1',
  address2: '3층',
  deliveryMemo: '부재시 경비실',
};

/**
 * 결제 화면이 채워 보내는 동의. 배송지와 같은 이유로 모든 다건 주문 호출에 붙는다 —
 * 이 주문은 이름·연락처·주소를 택배사로 넘기므로 동의 이력이 함께 남아야 하고, 서버는
 * 필수 항목이 빠진 요청을 400 으로 거절한다.
 *
 * <p>거절한 선택 항목({@code agreed: false})도 함께 실려 나간다. "물었고 거절했다"와
 * "묻지 않았다"는 다른 사실이라, 목록에서 빼면 뒤엣것과 구별되지 않는다.
 */
const consents = [
  { termsCode: 'THIRD_PARTY_DELIVERY', termsVersion: 2, agreed: true },
  { termsCode: 'MARKETING_MESSAGE', termsVersion: 1, agreed: false },
];

describe('orderApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('주문을 생성한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: order });

    const result = await orderApi.createOrder({ userId: 7, items: [] } as never);

    expect(api.post).toHaveBeenCalledWith('/orders', { userId: 7, items: [] });
    expect(result.id).toBe(100);
  });

  it('다건 주문은 금액 없이 라인만 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...order, amount: 27000 } });

    const result = await orderApi.createMultiItemOrder(
      7, [{ productId: 1, quantity: 3 }], address, consents, 'WELCOME10', 'key-1');

    expect(api.post).toHaveBeenCalledWith(
      '/orders/multi',
      {
        userId: 7,
        lines: [{ productId: 1, quantity: 3 }],
        couponCode: 'WELCOME10',
        shippingAddress: address,
        consents,
      },
      { headers: { 'Idempotency-Key': 'key-1' } },
    );
    expect(result.amount).toBe(27000);
  });

  it('쿠폰·멱등 키가 없으면 couponCode 는 null 이고 헤더는 붙지 않는다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: order });

    await orderApi.createMultiItemOrder(7, [{ productId: 1, quantity: 1 }], address, consents);

    expect(api.post).toHaveBeenCalledWith(
      '/orders/multi',
      {
        userId: 7,
        lines: [{ productId: 1, quantity: 1 }],
        couponCode: null,
        shippingAddress: address,
        consents,
      },
      undefined,
    );
  });

  /**
   * 동의 목록은 <b>손대지 않고</b> 그대로 실려 나가야 한다. 특히 거절한 항목을 빼면 서버가 받는
   * 사실이 "묻지 않았다"로 바뀌므로, 보내는 쪽에서 걸러 내지 않는지를 따로 못박는다.
   *
   * <p>{@code toHaveBeenCalledWith} 는 값이 {@code undefined} 인 속성을 없는 것처럼 보므로,
   * 위의 두 검사만으로는 consents 가 통째로 빠져도 초록으로 남는다. 여기서는 실린 값을 꺼내
   * 직접 비교한다.
   */
  it('거절한 동의도 걸러 내지 않고 그대로 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: order });

    await orderApi.createMultiItemOrder(7, [{ productId: 1, quantity: 1 }], address, consents);

    const body = vi.mocked(api.post).mock.calls[0][1] as { consents: typeof consents };
    expect(body.consents).toEqual(consents);
    expect(body.consents.map((c) => c.agreed)).toEqual([true, false]);
  });

  it('주문 단건을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: order });

    const result = await orderApi.getOrder(100);

    expect(api.get).toHaveBeenCalledWith('/orders/100');
    expect(result.status).toBe('CREATED');
  });

  it('사용자별 주문 목록을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [order] });

    const result = await orderApi.getUserOrders(7);

    expect(api.get).toHaveBeenCalledWith('/orders/user/7');
    expect(result).toHaveLength(1);
  });

  it('주문을 취소한다', async () => {
    vi.mocked(api.patch).mockResolvedValueOnce({ data: { ...order, status: 'CANCELLED' } });

    const result = await orderApi.cancelOrder(100);

    expect(api.patch).toHaveBeenCalledWith('/orders/100/cancel');
    expect(result.status).toBe('CANCELLED');
  });

  it('취소 불가 상태면 오류가 전파된다 (상태머신은 서버가 강제)', async () => {
    vi.mocked(api.patch).mockRejectedValueOnce({ response: { status: 400 } });

    await expect(orderApi.cancelOrder(100)).rejects.toMatchObject({ response: { status: 400 } });
  });
});
