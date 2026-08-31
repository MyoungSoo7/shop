import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>기본은 미출고만이다.</b> 셀러가 여기서 답해야 하는 질문은 "얼마 팔렸나" 가 아니라
 * "무엇을 아직 안 보냈나" 다. 전체를 먼저 보여 주면 보낼 것이 그 안에 묻힌다.
 *
 * <p>② <b>추정 결제시각은 확정과 같은 글자로 그리지 않는다.</b> 셀러는 이 날짜로 출고 기한을
 * 센다. 추정값을 확정처럼 그리면 기한을 잘못 세고도 아무도 모른다.
 *
 * <p>③ <b>송장 등록 뒤 낙관적으로 '출고됨' 으로 바꾸지 않는다.</b> 서버는 202(접수)만 답하고
 * 실제 전이는 order-service 가 이벤트로 한다. 화면이 앞질러 그리면 실패한 등록이 화면에서만
 * 성공으로 남는다.
 *
 * <p>④ <b>이미 등록된 건에는 입력칸을 그리지 않는다.</b> 서버가 거절할 조작을 화면이 권하면
 * 셀러는 자기가 잘못한 줄 안다.
 *
 * <p>⑤ <b>모르는 값을 아는 척하지 않는다.</b> 주문상태 null 을 'CREATED' 로 채우면 취소 건이
 * 정상으로 보인다.
 */

vi.mock('@/api/seller', async () => {
  const actual = await vi.importActual<typeof import('@/api/seller')>('@/api/seller');
  return {
    ...actual,
    sellerApi: {
      profile: vi.fn(), members: vi.fn(), submissions: vi.fn(), submission: vi.fn(),
      createSubmission: vi.fn(), updateSubmission: vi.fn(), submitSubmission: vi.fn(),
      orders: vi.fn(), order: vi.fn(), registerShipment: vi.fn(),
      pendingSubmissions: vi.fn(), approveSubmission: vi.fn(), rejectSubmission: vi.fn(),
    },
  };
});

const { sellerApi } = await import('@/api/seller');
const { default: SellerOrdersPage } = await import('@/pages/seller/SellerOrdersPage');

const mock = vi.mocked(sellerApi);

const order = (over: Record<string, unknown> = {}) => ({
  orderId: 10231,
  paymentId: 55,
  // 확정 문자열을 검사하지 않는다 — toLocaleString 은 실행 환경의 시간대에 따라 달라진다.
  capturedAt: '2026-08-27T13:40:00',
  capturedAtEstimated: false,
  amount: 50000,
  refundedAmount: 0,
  netAmount: 50000,
  paymentMethod: 'CARD',
  orderStatus: 'PAID',
  productId: 11,
  productName: '텀블러',
  shipmentRegistered: false,
  carrier: null,
  trackingNumber: null,
  shipmentRequestedAt: null,
  ...over,
});

const listing = (over: Record<string, unknown> = {}) => ({
  content: [order()], page: 0, size: 20, totalElements: 1, totalPages: 1, ...over,
});

const httpError = (status: number, message: string) => ({ response: { status, data: { message } } });

const DEFAULT_FILTER = { from: null, to: null, orderId: null, unshippedOnly: true };

const draw = () => render(<MemoryRouter><SellerOrdersPage /></MemoryRouter>);

describe('SellerOrdersPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mock.orders.mockResolvedValue(listing());
  });

  it('처음 불러오는 동안에는 진행 표시를 낸다', () => {
    mock.orders.mockReturnValue(new Promise(() => {}));

    draw();

    expect(screen.getByTestId('orders-loading')).toBeInTheDocument();
  });

  /** ①번 규율 — 빈 날짜를 조건으로 싣지 않고, 미출고만 켠 채 시작한다. */
  it('첫 조회는 미출고만 조건으로 부른다', async () => {
    draw();

    await screen.findByTestId('orders-table');
    expect(mock.orders).toHaveBeenCalledWith({ unshippedOnly: true }, 0, 20);
    expect(screen.getByTestId('filter-unshipped')).toBeChecked();
  });

  it('보낼 것이 없을 때와 주문 자체가 없을 때를 다르게 적는다', async () => {
    mock.orders.mockResolvedValue(listing({ content: [], totalElements: 0, totalPages: 0 }));
    draw();

    expect(await screen.findByTestId('orders-empty')).toHaveTextContent('보낼 주문이 없습니다');

    await userEvent.click(screen.getByTestId('filter-unshipped'));
    await userEvent.click(screen.getByTestId('filter-apply'));

    await waitFor(() => expect(screen.getByTestId('orders-empty')).toHaveTextContent('주문이 없습니다'));
  });

  /** ②번 규율. */
  it('추정 결제시각인 행에만 별표가 붙는다', async () => {
    mock.orders.mockResolvedValue(listing({
      content: [order({ capturedAtEstimated: true }), order({ orderId: 10232, paymentId: 56 })],
      totalElements: 2,
    }));

    draw();

    await screen.findByTestId('orders-table');
    expect(screen.getByTestId('estimated-10231')).toBeInTheDocument();
    expect(screen.queryByTestId('estimated-10232')).toBeNull();
  });

  /** ⑤번 규율. */
  it('주문상태가 없으면 확인 중이라 적지 CREATED 로 채우지 않는다', async () => {
    mock.orders.mockResolvedValue(listing({
      content: [order({ orderStatus: null, productName: null, productId: null })],
    }));

    draw();

    const table = await screen.findByTestId('orders-table');
    expect(table).toHaveTextContent('확인 중');
    expect(table).not.toHaveTextContent('CREATED');
    // 상품명이 없으면 번호로 부르고, 번호도 없으면 미상이라고 적는다.
    expect(table).toHaveTextContent('상품 미상');
  });

  it('환불이 없는 행은 0원이 아니라 —로 비운다', async () => {
    draw();

    const table = await screen.findByTestId('orders-table');
    expect(table).toHaveTextContent('50,000원');
    expect(table).toHaveTextContent('—');
  });

  it('날짜로 못 읽히는 값은 지어내지 않고 원문 그대로 둔다', async () => {
    mock.orders.mockResolvedValue(listing({ content: [order({ capturedAt: '알 수 없음' })] }));

    draw();

    expect(await screen.findByTestId('orders-table')).toHaveTextContent('알 수 없음');
  });

  it('출고 여부를 행마다 표시한다', async () => {
    mock.orders.mockResolvedValue(listing({
      content: [
        order({ shipmentRegistered: true, carrier: '한진', trackingNumber: '1234' }),
        order({ orderId: 10232, paymentId: 56 }),
      ],
      totalElements: 2,
    }));

    draw();

    expect(await screen.findByTestId('shipped-10231')).toHaveTextContent('한진 1234');
    expect(screen.getByTestId('unshipped-10232')).toHaveTextContent('미출고');
  });

  it('조회 조건은 채운 것만 숫자로 바꿔 싣는다', async () => {
    draw();
    await screen.findByTestId('orders-table');

    fireEvent.change(screen.getByTestId('filter-from'), { target: { value: '2026-08-01' } });
    fireEvent.change(screen.getByTestId('filter-to'), { target: { value: '2026-08-31' } });
    await userEvent.type(screen.getByTestId('filter-order-id'), '10231');
    await userEvent.click(screen.getByTestId('filter-apply'));

    await waitFor(() => expect(mock.orders).toHaveBeenLastCalledWith(
      { from: '2026-08-01', to: '2026-08-31', orderId: 10231, unshippedOnly: true }, 0, 20));
  });

  it('주문번호를 누르면 단건 상세를 연다', async () => {
    mock.order.mockResolvedValue(order({ netAmount: 40000, refundedAmount: 10000 }));
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.click(screen.getByTestId('order-10231'));

    const detail = await screen.findByTestId('order-detail');
    expect(detail).toHaveTextContent('40,000원');
    expect(mock.order).toHaveBeenCalledWith(10231);

    await userEvent.click(screen.getByTestId('detail-close'));
    expect(screen.queryByTestId('order-detail')).toBeNull();
  });

  it('상세 조회가 실패하면 표는 그대로 두고 오류만 알린다', async () => {
    mock.order.mockRejectedValue(httpError(404, '주문을 찾을 수 없습니다.'));
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.click(screen.getByTestId('order-10231'));

    expect(await screen.findByTestId('orders-error')).toHaveTextContent('주문을 찾을 수 없습니다.');
    expect(screen.getByTestId('orders-table')).toBeInTheDocument();
  });

  /** ④번 규율. */
  it('이미 송장이 등록된 건에는 입력칸 대신 등록된 값을 적는다', async () => {
    mock.order.mockResolvedValue(order({
      shipmentRegistered: true, carrier: '한진', trackingNumber: '1234',
      shipmentRequestedAt: null,
    }));
    draw();
    await screen.findByTestId('orders-table');

    await userEvent.click(screen.getByTestId('order-10231'));

    expect(await screen.findByTestId('detail-shipped')).toHaveTextContent('한진 · 1234');
    expect(screen.queryByTestId('shipment-form-10231')).toBeNull();
  });

  it('택배사와 송장번호가 모두 차기 전에는 등록 버튼이 잠긴다', async () => {
    mock.order.mockResolvedValue(order());
    draw();
    await screen.findByTestId('orders-table');
    await userEvent.click(screen.getByTestId('order-10231'));
    await screen.findByTestId('shipment-form-10231');

    expect(screen.getByTestId('shipment-save')).toBeDisabled();

    await userEvent.type(screen.getByTestId('shipment-carrier'), '한진');
    expect(screen.getByTestId('shipment-save')).toBeDisabled();

    await userEvent.type(screen.getByTestId('shipment-tracking'), '  ');
    expect(screen.getByTestId('shipment-save')).toBeDisabled();
  });

  /** ③번 규율 — 이 테스트가 이 화면에서 가장 중요하다. */
  it('송장을 등록하면 화면이 앞질러 그리지 않고 목록을 다시 읽는다', async () => {
    mock.order.mockResolvedValue(order());
    mock.registerShipment.mockResolvedValue(undefined);
    draw();
    await screen.findByTestId('orders-table');
    await userEvent.click(screen.getByTestId('order-10231'));
    await screen.findByTestId('shipment-form-10231');

    await userEvent.type(screen.getByTestId('shipment-carrier'), ' 한진 ');
    await userEvent.type(screen.getByTestId('shipment-tracking'), ' 1234 ');
    await userEvent.click(screen.getByTestId('shipment-save'));

    // 앞뒤 공백은 서버에 보내지 않는다 — 송장번호에 공백이 섞이면 조회가 안 된다.
    await waitFor(() => expect(mock.registerShipment).toHaveBeenCalledWith(10231, '한진', '1234'));
    // 상세는 닫히고 현재 조건 그대로 다시 읽는다. '출고됨' 으로 바꿔 그리지 않는다.
    await waitFor(() => expect(screen.queryByTestId('order-detail')).toBeNull());
    expect(mock.orders).toHaveBeenLastCalledWith(DEFAULT_FILTER, 0, 20);
  });

  it('송장 등록이 실패하면 상세를 닫지 않고 그 자리에 오류를 적는다', async () => {
    mock.order.mockResolvedValue(order());
    mock.registerShipment.mockRejectedValue(httpError(409, '이미 등록된 주문입니다.'));
    draw();
    await screen.findByTestId('orders-table');
    await userEvent.click(screen.getByTestId('order-10231'));
    await screen.findByTestId('shipment-form-10231');

    await userEvent.type(screen.getByTestId('shipment-carrier'), '한진');
    await userEvent.type(screen.getByTestId('shipment-tracking'), '1234');
    await userEvent.click(screen.getByTestId('shipment-save'));

    expect(await screen.findByTestId('shipment-error')).toHaveTextContent('이미 등록된 주문입니다.');
    expect(screen.getByTestId('order-detail')).toBeInTheDocument();
  });

  it('다음 쪽은 같은 조건으로 부르고, 첫 쪽에서 이전은 잠긴다', async () => {
    mock.orders.mockResolvedValue(listing({ totalElements: 45, totalPages: 3 }));
    draw();
    await screen.findByTestId('orders-table');

    expect(screen.getByTestId('orders-prev')).toBeDisabled();
    await userEvent.click(screen.getByTestId('orders-next'));

    await waitFor(() => expect(mock.orders).toHaveBeenLastCalledWith(DEFAULT_FILTER, 1, 20));

    await userEvent.click(screen.getByTestId('orders-prev'));
    await waitFor(() => expect(mock.orders).toHaveBeenLastCalledWith(DEFAULT_FILTER, 0, 20));
  });

  it('전체 건수와 쪽 번호를 적는다', async () => {
    mock.orders.mockResolvedValue(listing({ totalElements: 1234, totalPages: 62 }));

    draw();

    expect(await screen.findByTestId('orders-total')).toHaveTextContent('전체 1,234건 · 1/62쪽');
  });

  it('조회에 실패하면 오류를 그린다', async () => {
    mock.orders.mockRejectedValue(httpError(500, ''));

    draw();

    expect(await screen.findByTestId('orders-error')).toHaveTextContent('주문을 불러오지 못했습니다');
  });
});
