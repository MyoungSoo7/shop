import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ReturnRequestAdminPage from '@/pages/ReturnRequestAdminPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { adminReturnRequestApi, type ReturnRequestResponse } from '@/api/returnRequest';

vi.mock('@/api/returnRequest', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/returnRequest')>();
  return {
    ...actual,
    adminReturnRequestApi: {
      queue: vi.fn(), approve: vi.fn(), reject: vi.fn(), collect: vi.fn(),
      shipExchange: vi.fn(), refund: vi.fn(), changeRefundAccount: vi.fn(),
      registerReturnWaybill: vi.fn(),
    },
  };
});

const mocked = vi.mocked(adminReturnRequestApi);

const request = (over: Partial<ReturnRequestResponse> = {}): ReturnRequestResponse => ({
  id: 9, orderId: 42, userId: 7, type: 'RETURN', status: 'REQUESTED',
  reasonCode: 'DEFECT', reasonDetail: null,
  refundBankCode: null, refundAccountNumberMasked: null, refundAccountHolder: null,
  awaitsRefundAccount: false,
  returnCarrier: null, returnTrackingNumber: null,
  exchangeCarrier: null, exchangeTrackingNumber: null,
  requestedBy: 'user7', processedBy: null, rejectReason: null,
  requestedAt: '2026-08-27T09:00:00', approvedAt: null, collectedAt: null,
  exchangeShippedAt: null, completedAt: null, updatedAt: '2026-08-27T09:00:00',
  ...over,
});

const draw = () => render(<ToastProvider><ReturnRequestAdminPage /></ToastProvider>);

beforeEach(() => {
  vi.clearAllMocks();
  mocked.queue.mockResolvedValue([]);
});

/**
 * 이 화면의 존재 이유는 <b>승인과 환불을 떼어 놓는 것</b>이다. 주문 승인 큐의 "환불 승인" 버튼
 * 하나로 반품을 처리하면 물건이 돌아왔는지를 묻는 자리가 없어, 회수 전에 돈이 나간다.
 */
describe('ReturnRequestAdminPage — 단계마다 다른 버튼', () => {
  it('신청됨에는 승인·거절만 있고 환불·회수 확인은 없다', async () => {
    mocked.queue.mockResolvedValue([request()]);
    draw();

    await waitFor(() => expect(screen.getByRole('button', { name: '승인' })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '거절' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '회수 확인' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '환불 실행' })).not.toBeInTheDocument();
  });

  /** 출고 전 취소는 회수할 물건이 없다 — 승인이 곧 환불이고, 버튼 라벨이 그 사실을 말해야 한다. */
  it('취소 신청의 승인 버튼은 환불까지 한다고 적는다', async () => {
    mocked.queue.mockResolvedValue([request({ type: 'CANCEL' })]);
    draw();

    await waitFor(() => expect(screen.getByRole('button', { name: '승인 (환불까지)' })).toBeInTheDocument());
  });

  /**
   * 회수 확인은 송장을 근거로만 찍힌다(서버가 그렇게 막는다). 고객이 송장을 안 적었으면
   * 대리 등록 자리가 떠야 한다 — 없으면 그 신청은 영영 다음 단계로 못 간다.
   */
  it('승인됐는데 회수 송장이 없으면 회수 확인 대신 대리 등록이 뜬다', async () => {
    mocked.queue.mockResolvedValue([request({ status: 'APPROVED' })]);
    draw();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: '회수 송장 대리 등록' })).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '회수 확인' })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('회수 택배사'), { target: { value: 'CJ대한통운' } });
    fireEvent.change(screen.getByLabelText('회수 송장번호'), { target: { value: '1234' } });
    mocked.registerReturnWaybill.mockResolvedValue(
      request({ status: 'APPROVED', returnCarrier: 'CJ대한통운', returnTrackingNumber: '1234' }));
    fireEvent.click(screen.getByRole('button', { name: '회수 송장 대리 등록' }));

    await waitFor(() => expect(mocked.registerReturnWaybill).toHaveBeenCalledWith(
      9, { carrier: 'CJ대한통운', trackingNumber: '1234' }));
    // 응답으로 그 줄이 갈아끼워져 다음 단계 버튼이 곧바로 뜬다.
    await waitFor(() => expect(screen.getByRole('button', { name: '회수 확인' })).toBeInTheDocument());
  });

  it('회수 완료된 반품은 환불, 교환은 재배송 — 서로 뜨지 않는다', async () => {
    mocked.queue.mockResolvedValue([
      request({ id: 9, status: 'COLLECTED', returnCarrier: 'CJ', returnTrackingNumber: '1' }),
      request({ id: 10, type: 'EXCHANGE', status: 'COLLECTED', returnCarrier: 'CJ', returnTrackingNumber: '2' }),
    ]);
    draw();

    await waitFor(() => expect(screen.getByRole('button', { name: '환불 실행' })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '교환품 재배송' })).toBeInTheDocument();
    // 반품에 재배송이, 교환에 환불이 붙지 않는다 — 각각 하나씩만 있다.
    expect(screen.getAllByRole('button', { name: '환불 실행' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: '교환품 재배송' })).toHaveLength(1);
  });
});

describe('ReturnRequestAdminPage — 계좌 없이 환불되지 않는다', () => {
  /**
   * 서버도 막지만 여기서 막지 않으면 운영자는 환불을 누르고 실패 토스트를 본 뒤에야
   * 계좌가 없다는 사실을 안다. 그것도 하루에 수십 번.
   */
  it('계좌 대기 중이면 환불 버튼이 잠기고 이유가 적힌다', async () => {
    mocked.queue.mockResolvedValue([request({
      status: 'COLLECTED', awaitsRefundAccount: true,
      returnCarrier: 'CJ', returnTrackingNumber: '1',
    })]);
    draw();

    await waitFor(() => expect(screen.getByRole('button', { name: '환불 실행' })).toBeDisabled());
    expect(screen.getByText(/환불받을 계좌가 없습니다/)).toBeInTheDocument();
  });

  it('계좌를 등록하면 잠금이 풀린다', async () => {
    mocked.queue.mockResolvedValue([request({
      status: 'COLLECTED', awaitsRefundAccount: true,
      returnCarrier: 'CJ', returnTrackingNumber: '1',
    })]);
    draw();

    await waitFor(() => expect(screen.getByLabelText('환불 은행')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('환불 은행'), { target: { value: '088' } });
    fireEvent.change(screen.getByLabelText('환불 계좌번호'), { target: { value: '110123456789' } });
    fireEvent.change(screen.getByLabelText('예금주'), { target: { value: '홍길동' } });

    mocked.changeRefundAccount.mockResolvedValue(request({
      status: 'COLLECTED', awaitsRefundAccount: false,
      refundBankCode: '088', refundAccountNumberMasked: '1101****6789', refundAccountHolder: '홍길동',
      returnCarrier: 'CJ', returnTrackingNumber: '1',
    }));
    fireEvent.click(screen.getByRole('button', { name: '계좌 등록' }));

    await waitFor(() => expect(mocked.changeRefundAccount).toHaveBeenCalledWith(
      9, { bankCode: '088', accountNumber: '110123456789', holderName: '홍길동' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '환불 실행' })).toBeEnabled());
    // 코드가 아니라 은행 이름으로 되돌아와야 송금 전에 눈에 걸린다.
    expect(screen.getByText(/신한은행 1101\*\*\*\*6789/)).toBeInTheDocument();
  });
});

describe('ReturnRequestAdminPage — 목록', () => {
  it('기본 탭은 상태를 주지 않는다 — 열린 신청 전부를 서버가 고른다', async () => {
    draw();
    await waitFor(() => expect(mocked.queue).toHaveBeenCalledWith(undefined, 100));
  });

  it('탭을 고르면 그 상태만 다시 받는다', async () => {
    draw();
    // 탭이 그려지길 기다린다 — 첫 조회가 불렸다는 사실만으로는 렌더가 끝났다는 보장이 없다.
    fireEvent.click(await screen.findByRole('button', { name: '회수 완료' }));

    await waitFor(() => expect(mocked.queue).toHaveBeenLastCalledWith(['COLLECTED'], 100));
  });

  /** 조회 실패를 0건으로 뭉개면 밀린 반품을 못 본 채로 넘어간다. */
  it('조회 실패와 0건을 구분한다', async () => {
    mocked.queue.mockRejectedValue(new Error('boom'));
    draw();
    await waitFor(() =>
      expect(screen.getByText('신청 목록을 불러오지 못했습니다.')).toBeInTheDocument());
    expect(screen.queryByText('해당하는 반품·교환 신청이 없습니다.')).not.toBeInTheDocument();
  });

  it('사유 코드는 한글 라벨로 나온다', async () => {
    mocked.queue.mockResolvedValue([request({ reasonCode: 'WRONG_OPTION' })]);
    draw();
    await waitFor(() => expect(screen.getByText(/옵션을 잘못 선택했어요/)).toBeInTheDocument());
  });

  /** 처리 직후 행이 사라지면 방금 무엇을 눌렀는지 확인할 수 없다. */
  it('승인한 행은 사라지지 않고 상태만 바뀐다', async () => {
    mocked.queue.mockResolvedValue([request()]);
    mocked.approve.mockResolvedValue(request({ status: 'APPROVED' }));
    draw();

    await waitFor(() => expect(screen.getByRole('button', { name: '승인' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    await waitFor(() => expect(screen.getByText('승인됨')).toBeInTheDocument());
    expect(screen.getByText(/신청 #9 · 주문 #42/)).toBeInTheDocument();
  });

  /** 사유 없는 거절은 고객에게 "안 됩니다" 한 줄로 도착한다. */
  it('거절은 사유를 적어야 눌린다', async () => {
    mocked.queue.mockResolvedValue([request()]);
    draw();

    await waitFor(() => expect(screen.getByRole('button', { name: '거절' })).toBeDisabled());

    fireEvent.change(screen.getByLabelText('거절 사유'), { target: { value: '사용 흔적' } });
    mocked.reject.mockResolvedValue(request({ status: 'REJECTED', rejectReason: '사용 흔적' }));
    fireEvent.click(screen.getByRole('button', { name: '거절' }));

    await waitFor(() => expect(mocked.reject).toHaveBeenCalledWith(9, '사용 흔적'));
  });
});
