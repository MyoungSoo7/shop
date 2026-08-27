import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ShipmentTrackingPanel from '@/components/shipping/ShipmentTrackingPanel';
import {
  shippingApi,
  type ShipmentTracking,
  type ShipmentTrackingEvent,
} from '@/api/shipping';

// 서버로 나가는 호출만 가짜다. 상태 라벨은 진짜를 쓴다 — 그것까지 가짜면 화면이 enum 대신
// 사람이 읽는 말을 보여 주는지 검사하지 못한다.
vi.mock('@/api/shipping', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/shipping')>()),
  shippingApi: { tracking: vi.fn() },
}));

const mockedTracking = vi.mocked(shippingApi.tracking);

beforeEach(() => vi.clearAllMocks());

const event = (over: Partial<ShipmentTrackingEvent> = {}): ShipmentTrackingEvent => ({
  status: 'SHIPPED',
  source: 'INTERNAL',
  description: 'CJ대한통운에 상품을 인계했습니다.',
  location: null,
  occurredAt: '2026-08-20T09:00:00',
  ...over,
});

const tracking = (over: Partial<ShipmentTracking> = {}): ShipmentTracking => ({
  orderId: 42,
  status: 'SHIPPED',
  carrier: 'CJ대한통운',
  trackingNumber: 'TRK-1',
  events: [event()],
  carrierNote: null,
  ...over,
});

const httpError = (status: number) => ({
  isAxiosError: true,
  response: { status },
});

const open = async () => {
  fireEvent.click(screen.getByText(/배송 추적/));
  await waitFor(() => expect(mockedTracking).toHaveBeenCalledWith(42));
};

/**
 * 배송이 언제 어떻게 움직였는지 보여 주는 자리.
 *
 * <p>여기서 지키는 것 — <b>접혀 있는 동안 요청이 나가지 않는다</b>(외부 호출까지 딸려 나간다),
 * <b>택배사 조회가 실패해도 목록이 비지 않는다</b>(빈 목록은 "아무 일도 없었다"로 읽힌다),
 * <b>연동이 꺼진 것과 실패한 것을 구분한다</b>(쓰지 않는 연동의 부재를 알릴 이유가 없다).
 */
describe('ShipmentTrackingPanel — 배송이 언제 움직였는지', () => {
  it('접혀 있는 동안에는 서버를 부르지 않는다', () => {
    render(<ShipmentTrackingPanel orderId={42} />);

    expect(mockedTracking).not.toHaveBeenCalled();
  });

  it('펼치면 한 번만 읽는다 — 접었다 펴도 다시 부르지 않는다', async () => {
    mockedTracking.mockResolvedValue(tracking());
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();
    fireEvent.click(screen.getByText(/배송 추적/));
    fireEvent.click(screen.getByText(/배송 추적/));

    expect(mockedTracking).toHaveBeenCalledTimes(1);
  });

  it('이력을 서버가 준 순서대로 보여 주고, 상태는 사람이 읽는 말로 적는다', async () => {
    mockedTracking.mockResolvedValue(
      tracking({
        events: [
          event({ status: 'PENDING', description: '주문이 접수되어 배송 준비를 시작합니다.' }),
          event(),
        ],
      })
    );
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();

    const rows = screen.getByLabelText('배송 이력').querySelectorAll('li');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('주문이 접수되어');
    expect(rows[0].textContent).toContain('배송 준비 전');
    expect(rows[1].textContent).toContain('출고 완료');
  });

  it('택배사 스캔에는 출처를 표시한다 — 우리가 찍은 사실과 구분되어야 한다', async () => {
    mockedTracking.mockResolvedValue(
      tracking({
        events: [
          event(),
          event({
            source: 'CARRIER',
            status: 'IN_TRANSIT',
            description: '간선상차',
            location: '동서울허브',
          }),
        ],
      })
    );
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();

    const rows = screen.getByLabelText('배송 이력').querySelectorAll('li');
    expect(rows[0].textContent).not.toContain('택배사');
    expect(rows[1].textContent).toContain('택배사');
    expect(rows[1].textContent).toContain('동서울허브');
  });

  it('택배사 조회가 실패해도 내부 이력은 그대로 보이고 사유만 덧붙는다', async () => {
    mockedTracking.mockResolvedValue(
      tracking({ carrierNote: '택배사 배송 정보를 불러오지 못했습니다.' })
    );
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();

    expect(screen.getByLabelText('배송 이력').querySelectorAll('li')).toHaveLength(1);
    expect(screen.getByText('택배사 배송 정보를 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('연동이 꺼져 있으면(carrierNote 없음) 아무 경고도 띄우지 않는다', async () => {
    mockedTracking.mockResolvedValue(tracking());
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();

    expect(screen.queryByText(/불러오지 못했습니다/)).not.toBeInTheDocument();
  });

  it('운송장이 있으면 택배사와 함께 보여 준다', async () => {
    mockedTracking.mockResolvedValue(tracking());
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();

    expect(screen.getByText(/CJ대한통운 TRK-1/)).toBeInTheDocument();
  });

  it('배송 생성 전(404)은 장애가 아니라 정상 국면으로 적는다', async () => {
    mockedTracking.mockRejectedValue(httpError(404));
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();

    await waitFor(() =>
      expect(screen.getByText('아직 배송 정보가 없습니다.')).toBeInTheDocument()
    );
    expect(screen.queryByText(/불러오지 못했습니다/)).not.toBeInTheDocument();
  });

  it('조회 자체가 실패하면 실패라고 적는다', async () => {
    mockedTracking.mockRejectedValue(httpError(500));
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();

    await waitFor(() =>
      expect(screen.getByText('배송 추적을 불러오지 못했습니다.')).toBeInTheDocument()
    );
  });

  it('이력이 한 줄도 없으면 없다고 적는다 — 빈 화면으로 두지 않는다', async () => {
    mockedTracking.mockResolvedValue(tracking({ events: [] }));
    render(<ShipmentTrackingPanel orderId={42} />);

    await open();

    expect(screen.getByText('기록된 배송 이력이 없습니다.')).toBeInTheDocument();
  });
});
