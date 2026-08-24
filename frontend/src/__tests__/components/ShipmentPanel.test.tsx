import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import ShipmentPanel from '@/components/shipping/ShipmentPanel';
import { shippingApi, type Shipment } from '@/api/shipping';

vi.mock('@/api/shipping', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/shipping')>();
  return { ...actual, shippingApi: { get: vi.fn() } };
});

const shipment = (overrides: Partial<Shipment> = {}): Shipment => ({
  id: 1,
  orderId: 42,
  status: 'SHIPPED',
  recipientName: '홍길동',
  phone: '010-0000-0000',
  postalCode: '06236',
  address1: '서울시 강남구 테헤란로 1',
  address2: '101동 202호',
  deliveryMemo: null,
  carrier: 'CJ대한통운',
  trackingNumber: '1234567890',
  shippedAt: '2026-08-09T10:00:00',
  deliveredAt: null,
  ...overrides,
});

/** 404 를 흉내내는 오류 — apiErrorStatus 는 구조로 판별하므로 axios 인스턴스가 필요 없다. */
const httpError = (status: number) => ({ response: { status, data: {} } });

describe('ShipmentPanel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('배송 정보를 상태 라벨·운송장과 함께 보여준다', async () => {
    vi.mocked(shippingApi.get).mockResolvedValue(shipment());

    render(<ShipmentPanel orderId={42} />);

    await waitFor(() => expect(screen.getAllByText('출고 완료').length).toBeGreaterThan(0));
    expect(screen.getByText(/1234567890/)).toBeInTheDocument();
    expect(screen.getByText(/홍길동/)).toBeInTheDocument();
  });

  /**
   * 배송 미생성은 정상 상태다. 404 를 오류로 표시하면 사용자가 장애로 오해한다.
   */
  it('404 는 오류가 아니라 "배송 정보 없음"으로 표시한다', async () => {
    vi.mocked(shippingApi.get).mockRejectedValue(httpError(404));

    render(<ShipmentPanel orderId={42} />);

    await waitFor(() =>
      expect(screen.getByText('아직 배송 정보가 없습니다.')).toBeInTheDocument()
    );
    expect(screen.queryByText('배송 정보를 불러오지 못했습니다.')).not.toBeInTheDocument();
  });

  it('404 가 아닌 실패는 오류로 표시한다', async () => {
    vi.mocked(shippingApi.get).mockRejectedValue(httpError(500));

    render(<ShipmentPanel orderId={42} />);

    await waitFor(() =>
      expect(screen.getByText('배송 정보를 불러오지 못했습니다.')).toBeInTheDocument()
    );
  });

  it('부모가 배송을 넘겨주면 재조회하지 않는다', async () => {
    render(<ShipmentPanel orderId={42} shipment={shipment({ status: 'DELIVERED' })} />);

    expect(screen.getAllByText('배송 완료').length).toBeGreaterThan(0);
    expect(shippingApi.get).not.toHaveBeenCalled();
  });

  it('반품 건도 오류 없이 표시된다', async () => {
    vi.mocked(shippingApi.get).mockResolvedValue(shipment({ status: 'RETURNED' }));

    render(<ShipmentPanel orderId={42} />);

    await waitFor(() => expect(screen.getByText('반품됨')).toBeInTheDocument());
  });
});
