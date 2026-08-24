import { describe, it, expect, vi, beforeEach } from 'vitest';
import { cashReceiptApi, type CashReceipt } from '@/api/cashReceipt';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const receipt: CashReceipt = {
  id: 1,
  paymentId: 100,
  orderId: 55,
  purpose: 'INCOME_DEDUCTION',
  purposeLabel: '소득공제',
  identifierType: 'MOBILE',
  maskedIdentifier: '****1234',
  totalAmount: 11_000,
  supplyAmount: 10_000,
  vatAmount: 1_000,
  status: 'ISSUED',
  approvalNumber: 'A-0001',
  failureReason: null,
  issuedAt: '2026-08-22T10:00:00Z',
};

describe('cashReceiptApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('주문 기준 경로로 조회한다', async () => {
    // 고객 화면이 들고 있는 식별자는 주문번호다. 결제 id 는 노출되지 않는다.
    vi.mocked(api.get).mockResolvedValueOnce({ status: 200, data: receipt });

    const result = await cashReceiptApi.getByOrder(55);

    expect(api.get).toHaveBeenCalledWith('/api/payments/by-order/55/cash-receipt');
    expect(result?.approvalNumber).toBe('A-0001');
  });

  it('204 는 "발급 이력 없음" 이므로 null 로 바꾼다', async () => {
    // 204 의 body 를 그대로 돌려주면 화면이 빈 객체를 영수증으로 그린다.
    vi.mocked(api.get).mockResolvedValueOnce({ status: 204, data: '' });

    expect(await cashReceiptApi.getByOrder(55)).toBeNull();
  });

  it('발급 요청은 용도·식별번호 종류·값을 그대로 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ status: 200, data: receipt });

    const result = await cashReceiptApi.issueForOrder(55, {
      purpose: 'EXPENSE_PROOF',
      identifierType: 'BUSINESS_NUMBER',
      identifierValue: '1234567890',
    });

    expect(api.post).toHaveBeenCalledWith('/api/payments/by-order/55/cash-receipt', {
      purpose: 'EXPENSE_PROOF',
      identifierType: 'BUSINESS_NUMBER',
      identifierValue: '1234567890',
    });
    expect(result.id).toBe(1);
  });

  it('응답의 식별번호는 마스킹된 값뿐이다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ status: 200, data: receipt });

    const result = await cashReceiptApi.getByOrder(55);

    // 서버가 원문을 내려주지 않는다 — 저장하지 않은 데이터는 새지 않는다.
    expect(result?.maskedIdentifier).toBe('****1234');
    expect(result).not.toHaveProperty('identifierValue');
  });

  it('발급 실패는 예외가 아니라 상태와 사유로 온다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      status: 200,
      data: { ...receipt, status: 'FAILED', approvalNumber: null, failureReason: '국세청 응답 지연' },
    });

    const result = await cashReceiptApi.issueForOrder(55, {
      purpose: 'INCOME_DEDUCTION',
      identifierType: 'MOBILE',
      identifierValue: '01012341234',
    });

    expect(result.status).toBe('FAILED');
    expect(result.failureReason).toBe('국세청 응답 지연');
  });
});
