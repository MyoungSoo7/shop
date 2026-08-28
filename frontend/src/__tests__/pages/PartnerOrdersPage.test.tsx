import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>잘린 CSV 는 반드시 잘렸다고 말한다.</b> 서버는 큰 기간을 잘라 주되 응답 헤더로 알린다.
 * 화면이 그 헤더를 흘리면 파일은 열리고 숫자도 들어 있으므로 <i>틀렸다는 신호가 어디에도 남지
 * 않는다</i> — 그 CSV 가 회계로 간다. 실패 중에서도 조용한 쪽이라 오래 산다.
 *
 * <p>② <b>모르는 값을 아는 척하지 않는다.</b> 주문상태가 null 인 것은 결제는 왔는데 그 주문의
 * order.created 가 아직 안 온 상태다. 'CREATED' 로 채우면 취소된 주문이 정상으로 보인다.
 *
 * <p>③ <b>추정 표시는 행마다 붙는다.</b> 상단에 한 번만 적으면 어느 행이 추정인지 알 수 없어
 * "이 목록 전체를 못 믿는다" 가 된다.
 *
 * <p>④ <b>빈 날짜를 조건으로 보내지 않는다.</b> 빈 문자열은 "조건 없음" 이지 날짜가 아니다 —
 * 그대로 보내면 서버가 파싱하다 400 을 낸다.
 */

vi.mock('@/api/partner', async () => {
  const actual = await vi.importActual<typeof import('@/api/partner')>('@/api/partner');
  return {
    ...actual,
    partnerApi: {
      me: vi.fn(), members: vi.fn(), dashboard: vi.fn(),
      orders: vi.fn(), order: vi.fn(), exportOrders: vi.fn(),
    },
  };
});

vi.mock('@/lib/fileTransfer', () => ({ downloadBlob: vi.fn().mockResolvedValue(null) }));

const { partnerApi } = await import('@/api/partner');
const { downloadBlob } = await import('@/lib/fileTransfer');
const { default: PartnerOrdersPage } = await import('@/pages/partner/PartnerOrdersPage');

const mock = vi.mocked(partnerApi);

const order = (over: Record<string, unknown> = {}) => ({
  orderId: 10231,
  paymentId: 55,
  capturedAt: '2026-08-27T13:40:00',
  capturedAtEstimated: false,
  amount: '50000',
  refundedAmount: '0',
  netAmount: '50000',
  paymentMethod: 'CARD',
  orderStatus: 'PAID',
  productId: 11,
  productName: '텀블러',
  ...over,
});

const page = (over: Record<string, unknown> = {}) => ({
  content: [order()], page: 0, size: 20, totalElements: 1, totalPages: 1, ...over,
});

const draw = () => render(<MemoryRouter><PartnerOrdersPage /></MemoryRouter>);

describe('PartnerOrdersPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(downloadBlob).mockResolvedValue(null);
    mock.orders.mockResolvedValue(page());
  });

  /** ④번 규율 — 첫 조회는 조건 없이 부른다. */
  it('첫 조회는 빈 날짜를 조건으로 싣지 않는다', async () => {
    draw();

    await screen.findByTestId('orders-table');
    expect(mock.orders).toHaveBeenCalledWith({ from: null, to: null, orderId: null }, 0, 20);
  });

  it('결제 건만 집계된다는 계약을 화면에 적는다', async () => {
    draw();

    expect(await screen.findByText(/결제가 완료된 건만 집계됩니다/)).toBeInTheDocument();
  });

  /** ②번 규율. */
  it('주문상태·결제수단·상품이 없으면 비워 두지 채우지 않는다', async () => {
    mock.orders.mockResolvedValue(page({
      content: [order({ orderStatus: null, paymentMethod: null, productId: null, productName: null })],
    }));

    draw();

    const table = await screen.findByTestId('orders-table');
    expect(table).toHaveTextContent('미확인 상품');
    expect(table).not.toHaveTextContent('CREATED');
    expect(table.querySelectorAll('td')[3]).toHaveTextContent('—');
  });

  /** ③번 규율. */
  it('추정 집계된 행에만 추정 표시가 붙는다', async () => {
    mock.orders.mockResolvedValue(page({
      content: [order({ capturedAtEstimated: true }), order({ orderId: 10232, paymentId: 56 })],
      totalElements: 2,
    }));

    draw();

    await screen.findByTestId('orders-table');
    expect(screen.getAllByText('추정')).toHaveLength(1);
  });

  it('조회 조건은 채운 것만 실린다', async () => {
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.type(screen.getByTestId('orders-order-id'), '10231');
    await userEvent.click(screen.getByTestId('orders-search'));

    await waitFor(() => {
      expect(mock.orders).toHaveBeenLastCalledWith({ from: null, to: null, orderId: 10231 }, 0, 20);
    });
  });

  it('주문번호 칸에는 숫자만 남는다', async () => {
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.type(screen.getByTestId('orders-order-id'), 'a1b2');

    expect(screen.getByTestId('orders-order-id')).toHaveValue('12');
  });

  it('행을 누르면 단건 상세를 연다', async () => {
    mock.order.mockResolvedValue(order({ refundedAmount: '10000', netAmount: '40000' }));
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.click(screen.getByTestId('partner-order-10231'));

    expect(await screen.findByTestId('partner-order-detail')).toHaveTextContent('40,000원');
    expect(mock.order).toHaveBeenCalledWith(10231);
  });

  it('단건 조회가 실패하면 표는 그대로 두고 오류만 알린다', async () => {
    mock.order.mockRejectedValue(new Error('nope'));
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.click(screen.getByTestId('partner-order-10231'));

    expect(await screen.findByTestId('orders-error')).toHaveTextContent('주문을 찾을 수 없습니다');
    expect(screen.getByTestId('orders-table')).toBeInTheDocument();
  });

  /** ①번 규율 — 이 테스트가 이 화면에서 가장 중요하다. */
  it('CSV 가 잘렸으면 잘렸다고 말하고 무엇을 할지까지 적는다', async () => {
    mock.exportOrders.mockResolvedValue({
      blob: new Blob(['a']), fileName: '주문.csv', totalMatched: 1200, truncated: true,
    });
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.click(screen.getByTestId('orders-export'));

    const notice = await screen.findByTestId('orders-notice');
    expect(notice).toHaveTextContent('1,200건 중 일부만');
    expect(notice).toHaveTextContent('기간을 나눠');
    expect(downloadBlob).toHaveBeenCalledWith(expect.any(Blob), '주문.csv');
  });

  it('잘리지 않았으면 겁주지 않고 건수만 알린다', async () => {
    mock.exportOrders.mockResolvedValue({
      blob: new Blob(['a']), fileName: 'partner_orders.csv', totalMatched: 3, truncated: false,
    });
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.click(screen.getByTestId('orders-export'));

    const notice = await screen.findByTestId('orders-notice');
    expect(notice).toHaveTextContent('3건을 받았습니다');
    expect(notice).not.toHaveTextContent('일부만');
  });

  it('내려받기가 실패하면 성공 안내를 남기지 않는다', async () => {
    mock.exportOrders.mockRejectedValue(new Error('boom'));
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.click(screen.getByTestId('orders-export'));

    expect(await screen.findByTestId('orders-error')).toHaveTextContent('내려받기에 실패했습니다');
    expect(screen.queryByTestId('orders-notice')).toBeNull();
  });

  it('다음 쪽은 같은 조건으로 부르고, 마지막 쪽에서는 잠긴다', async () => {
    mock.orders.mockResolvedValue(page({ totalElements: 45, totalPages: 3 }));
    draw();
    await screen.findByTestId('orders-table');

    expect(screen.getByTestId('orders-prev')).toBeDisabled();
    await userEvent.click(screen.getByTestId('orders-next'));

    await waitFor(() => expect(mock.orders).toHaveBeenLastCalledWith(
      { from: null, to: null, orderId: null }, 1, 20));
  });

  it('결과가 없으면 빈 표가 아니라 없다고 적는다', async () => {
    mock.orders.mockResolvedValue(page({ content: [], totalElements: 0, totalPages: 0 }));

    draw();

    expect(await screen.findByTestId('orders-empty')).toBeInTheDocument();
  });

  it('조회에 실패하면 오류를 그린다', async () => {
    mock.orders.mockRejectedValue(new Error('down'));

    draw();

    expect(await screen.findByTestId('orders-error')).toHaveTextContent('주문 내역을 불러오지 못했습니다');
  });

  it('처음 불러오는 동안에는 진행 표시를 낸다', () => {
    mock.orders.mockReturnValue(new Promise(() => {}));

    draw();

    expect(screen.getByTestId('orders-loading')).toBeInTheDocument();
  });
});
