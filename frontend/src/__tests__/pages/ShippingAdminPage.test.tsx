import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ShippingAdminPage from '@/pages/ShippingAdminPage';
import { adminApi } from '@/api/admin';
import { shippingApi } from '@/api/shipping';

const showToast = vi.fn();

vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

vi.mock('@/api/admin', () => ({ adminApi: { getAllOrders: vi.fn() } }));

vi.mock('@/api/shipping', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/shipping')>();
  return {
    ...actual,
    shippingApi: {
      get: vi.fn(),
      create: vi.fn(),
      ship: vi.fn(),
      markInTransit: vi.fn(),
      markDelivered: vi.fn(),
      markReturned: vi.fn(),
      changeAddress: vi.fn(),
      uploadTracking: vi.fn(),
    },
  };
});

const mockedAdmin = vi.mocked(adminApi);
const mockedShipping = vi.mocked(shippingApi);

const order = (over: Record<string, unknown> = {}) =>
  ({ id: 1, userId: 7, amount: 20000, status: 'PAID', createdAt: '2026-08-01T00:00:00Z', ...over }) as never;

const shipment = (over: Record<string, unknown> = {}) =>
  ({
    id: 10,
    orderId: 1,
    status: 'PENDING',
    recipientName: '홍길동',
    phone: '010-1111-2222',
    postalCode: '06236',
    address1: '서울시 강남구',
    address2: '101동 202호',
    deliveryMemo: null,
    carrier: null,
    trackingNumber: null,
    ...over,
  }) as never;

const notFound = { response: { status: 404 } };

beforeEach(() => {
  vi.clearAllMocks();
  mockedAdmin.getAllOrders.mockResolvedValue([order()] as never);
  mockedShipping.get.mockResolvedValue(shipment());
});

const renderAndWait = async () => {
  render(<ShippingAdminPage />);
  await screen.findByText('주문 #1');
};

describe('ShippingAdminPage — 로드', () => {
  it('주문과 각 주문의 배송을 함께 읽어 보여 준다', async () => {
    await renderAndWait();

    expect(screen.getByText(/홍길동/)).toBeInTheDocument();
    expect(mockedShipping.get).toHaveBeenCalledWith(1);
  });

  it('배송이 없는 주문(404)은 생성 안내로 구분한다', async () => {
    mockedShipping.get.mockRejectedValue(notFound);
    await renderAndWait();

    expect(screen.getByText('배송이 생성되지 않았습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '배송 생성' })).toBeInTheDocument();
  });

  it('404 가 아닌 실패는 "확인 중"으로 남겨 값 없음과 구분한다', async () => {
    mockedShipping.get.mockRejectedValue(new Error('down'));
    await renderAndWait();

    expect(screen.getByText('배송 정보 확인 중...')).toBeInTheDocument();
  });

  it('주문 조회 실패는 화면에 사유를 남긴다', async () => {
    mockedAdmin.getAllOrders.mockRejectedValue({ response: { data: { message: '권한 없음' } } });
    render(<ShippingAdminPage />);

    expect(await screen.findByText('권한 없음')).toBeInTheDocument();
  });

  it('주문이 없으면 그 사실을 알린다', async () => {
    mockedAdmin.getAllOrders.mockResolvedValue([] as never);
    render(<ShippingAdminPage />);

    expect(await screen.findByText('표시할 주문이 없습니다.')).toBeInTheDocument();
  });

  it('새로고침은 목록을 다시 읽는다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '새로고침' }));

    await waitFor(() => expect(mockedAdmin.getAllOrders).toHaveBeenCalledTimes(2));
  });

  it('출고 전 주문만 보기 필터는 배송 완료 건을 숨긴다', async () => {
    mockedShipping.get.mockResolvedValue(shipment({ status: 'DELIVERED' }));
    await renderAndWait();

    await userEvent.click(screen.getByRole('checkbox'));

    expect(screen.getByText('표시할 주문이 없습니다.')).toBeInTheDocument();
  });

  it('출고 전 주문만 보기 필터는 미생성·PENDING 을 남긴다', async () => {
    mockedShipping.get.mockRejectedValue(notFound);
    await renderAndWait();

    await userEvent.click(screen.getByRole('checkbox'));

    expect(screen.getByText('주문 #1')).toBeInTheDocument();
  });
});

describe('ShippingAdminPage — 배송 생성', () => {
  it('필수 4개가 채워지기 전에는 생성 버튼이 잠긴다', async () => {
    mockedShipping.get.mockRejectedValue(notFound);
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '배송 생성' }));

    expect(screen.getByRole('button', { name: '배송 생성' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('받는 분'), '홍길동');
    await userEvent.type(screen.getByLabelText('연락처'), '010-1111-2222');
    await userEvent.type(screen.getByLabelText('우편번호'), '06236');
    expect(screen.getByRole('button', { name: '배송 생성' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('주소'), '서울시 강남구');
    expect(screen.getByRole('button', { name: '배송 생성' })).toBeEnabled();
  });

  it('생성하면 그 행만 배송 정보로 바뀐다', async () => {
    mockedShipping.get.mockRejectedValue(notFound);
    mockedShipping.create.mockResolvedValue(shipment());
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '배송 생성' }));
    await userEvent.type(screen.getByLabelText('받는 분'), '홍길동');
    await userEvent.type(screen.getByLabelText('연락처'), '010-1111-2222');
    await userEvent.type(screen.getByLabelText('우편번호'), '06236');
    await userEvent.type(screen.getByLabelText('주소'), '서울시 강남구');

    await userEvent.click(screen.getByRole('button', { name: '배송 생성' }));

    await waitFor(() => expect(mockedShipping.create).toHaveBeenCalledWith(1, expect.objectContaining({
      recipientName: '홍길동',
      postalCode: '06236',
    })));
    expect(showToast).toHaveBeenCalledWith('배송을 생성했습니다.', 'success');
  });

  it('생성 폼은 취소할 수 있다', async () => {
    mockedShipping.get.mockRejectedValue(notFound);
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '배송 생성' }));

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByLabelText('받는 분')).not.toBeInTheDocument();
  });
});

describe('ShippingAdminPage — 출고·상태 전이', () => {
  it('PENDING 은 출고 처리와 배송지 변경을 제공한다', async () => {
    await renderAndWait();

    expect(screen.getByRole('button', { name: '출고 처리' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '배송지 변경' })).toBeInTheDocument();
  });

  it('출고는 택배사·운송장이 모두 있어야 눌린다', async () => {
    mockedShipping.ship.mockResolvedValue(shipment({ status: 'SHIPPED', carrier: 'CJ', trackingNumber: '123' }));
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '출고 처리' }));

    expect(screen.getByRole('button', { name: '출고' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('택배사'), 'CJ');
    await userEvent.type(screen.getByLabelText('운송장 번호'), '123');
    await userEvent.click(screen.getByRole('button', { name: '출고' }));

    await waitFor(() =>
      expect(mockedShipping.ship).toHaveBeenCalledWith(1, { carrier: 'CJ', trackingNumber: '123' }),
    );
    expect(showToast).toHaveBeenCalledWith('출고 처리했습니다.', 'success');
  });

  it('출고 입력은 취소할 수 있다', async () => {
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '출고 처리' }));

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByLabelText('택배사')).not.toBeInTheDocument();
  });

  it('배송 중·완료 전이는 그대로 서버에 위임한다', async () => {
    mockedShipping.get.mockResolvedValue(shipment({ status: 'SHIPPED', carrier: 'CJ', trackingNumber: '123' }));
    mockedShipping.markInTransit.mockResolvedValue(shipment({ status: 'IN_TRANSIT' }));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '배송 중으로' }));

    await waitFor(() => expect(mockedShipping.markInTransit).toHaveBeenCalledWith(1));
    expect(showToast).toHaveBeenCalledWith('배송 중으로 완료', 'success');
  });

  it('배송 완료 처리', async () => {
    mockedShipping.get.mockResolvedValue(shipment({ status: 'IN_TRANSIT' }));
    mockedShipping.markDelivered.mockResolvedValue(shipment({ status: 'DELIVERED' }));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '배송 완료' }));

    await waitFor(() => expect(mockedShipping.markDelivered).toHaveBeenCalledWith(1));
  });

  it('반품 처리', async () => {
    mockedShipping.get.mockResolvedValue(shipment({ status: 'DELIVERED' }));
    mockedShipping.markReturned.mockResolvedValue(shipment({ status: 'RETURNED' }));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '반품 처리' }));

    await waitFor(() => expect(mockedShipping.markReturned).toHaveBeenCalledWith(1));
  });

  it('종료된 배송(RETURNED)은 더 이상 조작할 수 없다고 알린다', async () => {
    mockedShipping.get.mockResolvedValue(shipment({ status: 'RETURNED' }));
    await renderAndWait();

    expect(screen.getByText('종료된 배송입니다.')).toBeInTheDocument();
  });

  it('전이 실패는 토스트로 사유를 드러낸다 (조용히 삼키지 않는다)', async () => {
    mockedShipping.get.mockResolvedValue(shipment({ status: 'SHIPPED' }));
    mockedShipping.markInTransit.mockRejectedValue({
      response: { data: { message: '허용되지 않는 전이' } },
    });
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '배송 중으로' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('허용되지 않는 전이', 'error'));
  });

  it('배송지 변경은 기존 값을 채워 두고 저장한다', async () => {
    mockedShipping.changeAddress.mockResolvedValue(shipment({ recipientName: '김철수' }));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '배송지 변경' }));
    const form = screen.getByLabelText('받는 분') as HTMLInputElement;
    expect(form.value).toBe('홍길동');

    await userEvent.clear(form);
    await userEvent.type(form, '김철수');
    await userEvent.click(screen.getByRole('button', { name: '배송지 변경' }));

    await waitFor(() =>
      expect(mockedShipping.changeAddress).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ recipientName: '김철수' }),
      ),
    );
  });

  it('출고 이후에는 배송지 변경 버튼을 감춘다 (서버가 PENDING 에서만 허용)', async () => {
    mockedShipping.get.mockResolvedValue(shipment({ status: 'SHIPPED' }));
    await renderAndWait();

    expect(screen.queryByRole('button', { name: '배송지 변경' })).not.toBeInTheDocument();
  });

  it('운송장이 있으면 택배사와 함께 보여 준다', async () => {
    mockedShipping.get.mockResolvedValue(
      shipment({ status: 'SHIPPED', carrier: 'CJ대한통운', trackingNumber: '1234567890' }),
    );
    await renderAndWait();

    expect(screen.getByText(/CJ대한통운 1234567890/)).toBeInTheDocument();
  });
});

/**
 * 송장 일괄 업로드 — 수백 행이 한 번에 출고 처리되는 작업이라 미리보기가 문턱이다.
 *
 * <p>여기서 못박는 것은 "미리보기를 통과한 <b>그 파일</b>에만 반영이 열린다"는 것이다.
 * 파일을 바꿨는데 이전 판정이 남아 있으면 A 를 미리보고 B 를 반영하게 되고, 그 실수는
 * 출고가 나간 뒤에야 드러난다.
 */
describe('ShippingAdminPage — 송장 일괄 업로드', () => {
  const csv = () => new File(['order_id,carrier,tracking_number\n7,CJ,123'], 'tracking.csv',
    { type: 'text/csv' });

  const result = (over: Record<string, unknown> = {}) => ({
    applied: 2, failed: 1, dryRun: true,
    lines: [
      { orderId: 7, carrier: 'CJ', trackingNumber: '123', applied: true, reason: '' },
      { orderId: 9, carrier: 'CJ', trackingNumber: '', applied: false, reason: '운송장 번호가 없습니다' },
    ],
    ...over,
  });

  it('파일을 고르기 전에는 미리보기·반영 둘 다 잠겨 있다', async () => {
    mockedShipping.get.mockResolvedValue(shipment());
    await renderAndWait();

    expect(screen.getByRole('button', { name: '미리보기' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '반영' })).toBeDisabled();
  });

  it('미리보기는 dryRun=true 로 부르고 실패 행의 사유를 보여 준다', async () => {
    const user = userEvent.setup();
    mockedShipping.get.mockResolvedValue(shipment());
    mockedShipping.uploadTracking.mockResolvedValue(result());
    await renderAndWait();

    await user.upload(screen.getByLabelText('송장 CSV 파일'), csv());
    await user.click(screen.getByRole('button', { name: '미리보기' }));

    await waitFor(() => expect(screen.getByTestId('tracking-upload-result')).toBeInTheDocument());
    expect(mockedShipping.uploadTracking).toHaveBeenCalledWith(expect.any(File), true);
    expect(screen.getByText(/아직 아무것도 바뀌지 않았습니다/)).toBeInTheDocument();
    expect(screen.getByText(/운송장 번호가 없습니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '2건 반영' })).toBeEnabled();
  });

  it('파일을 바꾸면 이전 미리보기를 버려 반영 버튼이 다시 잠긴다', async () => {
    const user = userEvent.setup();
    mockedShipping.get.mockResolvedValue(shipment());
    mockedShipping.uploadTracking.mockResolvedValue(result());
    await renderAndWait();

    const input = screen.getByLabelText('송장 CSV 파일');
    await user.upload(input, csv());
    await user.click(screen.getByRole('button', { name: '미리보기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '2건 반영' })).toBeEnabled());

    // 다른 파일로 바꾼 순간, 앞선 판정은 이 파일의 것이 아니다.
    await user.upload(input, new File(['order_id,carrier,tracking_number\n8,LOTTE,999'],
      'other.csv', { type: 'text/csv' }));

    expect(screen.getByRole('button', { name: '반영' })).toBeDisabled();
    expect(screen.queryByTestId('tracking-upload-result')).not.toBeInTheDocument();
  });

  it('반영은 dryRun=false 로 부르고 목록을 다시 읽는다', async () => {
    const user = userEvent.setup();
    mockedShipping.get.mockResolvedValue(shipment());
    mockedShipping.uploadTracking.mockResolvedValue(result());
    await renderAndWait();

    await user.upload(screen.getByLabelText('송장 CSV 파일'), csv());
    await user.click(screen.getByRole('button', { name: '미리보기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '2건 반영' })).toBeEnabled());

    mockedShipping.uploadTracking.mockResolvedValue(result({ dryRun: false }));
    mockedAdmin.getAllOrders.mockClear();
    await user.click(screen.getByRole('button', { name: '2건 반영' }));

    await waitFor(() =>
      expect(mockedShipping.uploadTracking).toHaveBeenLastCalledWith(expect.any(File), false));
    expect(screen.getByText(/반영 완료/)).toBeInTheDocument();
    await waitFor(() => expect(mockedAdmin.getAllOrders).toHaveBeenCalled());
  });
});
