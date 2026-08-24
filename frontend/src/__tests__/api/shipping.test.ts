import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  shippingApi,
  nextShippingActions,
  SHIPPING_STATUS_LABEL,
  type Shipment,
} from '@/api/shipping';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
}));

const shipment: Shipment = {
  id: 1,
  orderId: 42,
  status: 'SHIPPED',
  recipientName: '홍길동',
  phone: '010-0000-0000',
  postalCode: '06236',
  address1: '서울시 강남구',
  address2: '101동 202호',
  deliveryMemo: '문 앞',
  carrier: 'CJ대한통운',
  trackingNumber: '1234567890',
  shippedAt: '2026-08-09T10:00:00',
  deliveredAt: null,
};

/** 서버는 { shipment: {...} } 로 감싸 준다. 껍데기가 화면까지 새면 모든 사용처가 res.shipment 를 쓴다. */
const envelope = { data: { shipment } };

describe('shippingApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('조회는 응답 껍데기를 벗겨 배송만 돌려준다', async () => {
    vi.mocked(api.get).mockResolvedValue(envelope);

    const result = await shippingApi.get(42);

    expect(api.get).toHaveBeenCalledWith('/orders/42/shipment');
    expect(result.status).toBe('SHIPPED');
    expect(result.trackingNumber).toBe('1234567890');
  });

  it('배송 생성은 주소를 그대로 본문에 싣는다', async () => {
    vi.mocked(api.post).mockResolvedValue(envelope);

    await shippingApi.create(42, {
      recipientName: '홍길동',
      phone: '010-0000-0000',
      postalCode: '06236',
      address1: '서울시 강남구',
    });

    expect(api.post).toHaveBeenCalledWith('/orders/42/shipment', {
      recipientName: '홍길동',
      phone: '010-0000-0000',
      postalCode: '06236',
      address1: '서울시 강남구',
    });
  });

  it('배송지 변경은 PATCH /address 로 간다', async () => {
    vi.mocked(api.patch).mockResolvedValue(envelope);

    await shippingApi.changeAddress(42, {
      recipientName: '김철수',
      phone: '010-1111-2222',
      postalCode: '13529',
      address1: '성남시 분당구',
    });

    expect(api.patch).toHaveBeenCalledWith(
      '/orders/42/shipment/address',
      expect.objectContaining({ recipientName: '김철수' })
    );
  });

  it('출고는 운송장 정보를 함께 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValue(envelope);

    await shippingApi.ship(42, { carrier: 'CJ대한통운', trackingNumber: '1234567890' });

    expect(api.post).toHaveBeenCalledWith('/orders/42/shipment/ship', {
      carrier: 'CJ대한통운',
      trackingNumber: '1234567890',
    });
  });

  it.each([
    ['markInTransit', '/orders/42/shipment/in-transit'],
    ['markDelivered', '/orders/42/shipment/delivered'],
    ['markReturned', '/orders/42/shipment/returned'],
  ] as const)('%s 는 %s 로 POST 한다', async (method, path) => {
    vi.mocked(api.post).mockResolvedValue(envelope);

    await shippingApi[method](42);

    expect(api.post).toHaveBeenCalledWith(path);
  });

  describe('nextShippingActions — 서버 상태머신의 사본', () => {
    it('출고 전에는 출고만 가능하다', () => {
      expect(nextShippingActions('PENDING')).toEqual(['ship']);
      expect(nextShippingActions('READY')).toEqual(['ship']);
    });

    /** SHIPPED 에서 배송완료로 바로 갈 수 있다 — 택배사 스캔이 누락되는 경우가 실제로 있다. */
    it('출고 후에는 배송중·배송완료 둘 다 가능하다', () => {
      expect(nextShippingActions('SHIPPED')).toEqual(['in-transit', 'delivered']);
    });

    it('배송중에서는 배송완료만 가능하다', () => {
      expect(nextShippingActions('IN_TRANSIT')).toEqual(['delivered']);
    });

    it('배송완료에서만 반품이 열린다', () => {
      expect(nextShippingActions('DELIVERED')).toEqual(['returned']);
    });

    it('반품은 종착이라 다음 전이가 없다', () => {
      expect(nextShippingActions('RETURNED')).toEqual([]);
    });
  });

  it('모든 상태에 한글 라벨이 있다 — 서버 enum 을 그대로 노출하지 않는다', () => {
    const statuses = ['PENDING', 'READY', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED'] as const;
    statuses.forEach((s) => {
      expect(SHIPPING_STATUS_LABEL[s]).toBeTruthy();
      expect(SHIPPING_STATUS_LABEL[s]).not.toBe(s);
    });
  });
});
