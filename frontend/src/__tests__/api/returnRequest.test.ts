import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  returnRequestApi,
  adminReturnRequestApi,
  isOpenReturnRequest,
  bankLabel,
  returnReasonLabel,
  BANK_OPTIONS,
  OPEN_RETURN_STATUSES,
  REASON_CODES_BY_TYPE,
  RETURN_REQUEST_STATUS_LABEL,
  RETURN_REQUEST_TYPE_LABEL,
  type ReturnRequestStatusValue,
  type ReturnRequestType,
} from '@/api/returnRequest';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

const record = { id: 9, orderId: 42, type: 'RETURN', status: 'REQUESTED' };

describe('returnRequestApi — 고객 경로', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.get).mockResolvedValue({ data: record });
    vi.mocked(api.post).mockResolvedValue({ data: record });
    vi.mocked(api.put).mockResolvedValue({ data: record });
  });

  /** 모든 경로가 orderId 를 지난다 — 그래야 서버가 소유권 대조를 건너뛸 수 없다. */
  it('모든 고객 경로는 주문 아래에 있다', async () => {
    await returnRequestApi.submit(42, { type: 'RETURN', reasonCode: 'DEFECT' });
    expect(api.post).toHaveBeenCalledWith('/orders/42/return-requests',
      { type: 'RETURN', reasonCode: 'DEFECT' });

    await returnRequestApi.history(42);
    expect(api.get).toHaveBeenCalledWith('/orders/42/return-requests');

    await returnRequestApi.registerWaybill(42, 9, { carrier: 'CJ', trackingNumber: '1' });
    expect(api.put).toHaveBeenCalledWith('/orders/42/return-requests/9/waybill',
      { carrier: 'CJ', trackingNumber: '1' });

    await returnRequestApi.changeRefundAccount(42, 9,
      { bankCode: '088', accountNumber: '110123456789', holderName: '홍길동' });
    expect(api.put).toHaveBeenCalledWith('/orders/42/return-requests/9/refund-account',
      { bankCode: '088', accountNumber: '110123456789', holderName: '홍길동' });
  });

  it('철회 사유는 선택이라 없으면 null 로 간다', async () => {
    await returnRequestApi.withdraw(42, 9);
    expect(api.post).toHaveBeenCalledWith('/orders/42/return-requests/9/withdraw', { reason: null });

    await returnRequestApi.withdraw(42, 9, '마음이 바뀌었어요');
    expect(api.post).toHaveBeenCalledWith('/orders/42/return-requests/9/withdraw',
      { reason: '마음이 바뀌었어요' });
  });
});

describe('adminReturnRequestApi — 운영 대기열', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.get).mockResolvedValue({ data: [record] });
    vi.mocked(api.post).mockResolvedValue({ data: record });
    vi.mocked(api.put).mockResolvedValue({ data: record });
  });

  /** 상태를 안 주면 서버가 열린 신청 전부를 고른다 — 빈 배열을 보내 전부를 거르면 안 된다. */
  it('상태를 주지 않으면 서버 기본값에 맡긴다', async () => {
    await adminReturnRequestApi.queue();
    expect(api.get).toHaveBeenCalledWith('/admin/return-requests',
      { params: { status: undefined, limit: 100 } });

    await adminReturnRequestApi.queue([]);
    expect(api.get).toHaveBeenLastCalledWith('/admin/return-requests',
      { params: { status: undefined, limit: 100 } });

    await adminReturnRequestApi.queue(['APPROVED'], 20);
    expect(api.get).toHaveBeenLastCalledWith('/admin/return-requests',
      { params: { status: ['APPROVED'], limit: 20 } });
  });

  /** 승인과 환불은 다른 동작이다 — 승인은 회수를 기다리고, 환불은 돈을 보낸다. */
  it('처리 동작마다 경로가 따로 있다', async () => {
    await adminReturnRequestApi.approve(9);
    expect(api.post).toHaveBeenCalledWith('/admin/return-requests/9/approve');

    await adminReturnRequestApi.reject(9, '사용 흔적');
    expect(api.post).toHaveBeenCalledWith('/admin/return-requests/9/reject', { reason: '사용 흔적' });

    await adminReturnRequestApi.collect(9);
    expect(api.post).toHaveBeenCalledWith('/admin/return-requests/9/collect');

    await adminReturnRequestApi.shipExchange(9, { carrier: 'CJ', trackingNumber: '2' });
    expect(api.post).toHaveBeenCalledWith('/admin/return-requests/9/exchange-shipment',
      { carrier: 'CJ', trackingNumber: '2' });

    await adminReturnRequestApi.refund(9);
    expect(api.post).toHaveBeenCalledWith('/admin/return-requests/9/refund');
  });
});

describe('표와 판정', () => {
  it('열린 신청은 세 상태뿐이다 — 종단은 다시 열리지 않는다', () => {
    OPEN_RETURN_STATUSES.forEach((s) => expect(isOpenReturnRequest(s)).toBe(true));
    ['COMPLETED', 'REJECTED', 'WITHDRAWN'].forEach((s) => expect(isOpenReturnRequest(s)).toBe(false));
  });

  it('서버 enum 전체에 한글 라벨이 있다 — 원시 문자열이 화면에 새지 않는다', () => {
    const statuses: ReturnRequestStatusValue[] =
      ['REQUESTED', 'APPROVED', 'COLLECTED', 'COMPLETED', 'REJECTED', 'WITHDRAWN'];
    statuses.forEach((s) => expect(RETURN_REQUEST_STATUS_LABEL[s]).not.toBe(s));

    const types: ReturnRequestType[] = ['RETURN', 'EXCHANGE', 'CANCEL'];
    types.forEach((t) => expect(RETURN_REQUEST_TYPE_LABEL[t]).not.toBe(t));
  });

  /** 표에 없는 코드까지 빈칸으로 만들면, 사유가 사라진 신청이 대기열에 선다. */
  it('모르는 사유 코드는 코드 자체를 보여준다', () => {
    expect(returnReasonLabel('DEFECT')).toBe('상품 불량·파손');
    expect(returnReasonLabel('WHATEVER_NEW_CODE')).toBe('WHATEVER_NEW_CODE');
  });

  it('고를 수 있는 사유는 모두 라벨을 가진다', () => {
    Object.values(REASON_CODES_BY_TYPE).flat().forEach((code) =>
      expect(returnReasonLabel(code)).not.toBe(code)
    );
  });

  /**
   * 늦어서 필요 없어진 주문은 교환이 아니라 반품이다. 고를 수 있게 두면 회수·재배송을
   * 한 바퀴 돌고 나서 결국 환불로 다시 온다.
   */
  it('교환 사유에는 배송 지연이 없다', () => {
    expect(REASON_CODES_BY_TYPE.EXCHANGE).not.toContain('DELIVERY_DELAY');
    expect(REASON_CODES_BY_TYPE.RETURN).toContain('DELIVERY_DELAY');
  });

  it('은행 코드는 중복 없이 이름으로 되돌아온다', () => {
    const codes = BANK_OPTIONS.map((b) => b.code);
    expect(new Set(codes).size).toBe(codes.length);
    expect(bankLabel('088')).toBe('신한은행');
    // 표에 없는 코드는 코드라도 보여준다 — 빈칸이면 송금하는 사람이 은행을 못 고른다.
    expect(bankLabel('999')).toBe('999');
    expect(bankLabel(null)).toBe('');
  });
});
