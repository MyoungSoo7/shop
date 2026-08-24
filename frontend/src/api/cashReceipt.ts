import api from './axios';

/** 발급 용도 — 소득공제(개인) / 지출증빙(사업자). 쓸 수 있는 식별번호가 서로 다르다. */
export type CashReceiptPurpose = 'INCOME_DEDUCTION' | 'EXPENSE_PROOF';

/** 식별번호 종류. 주민등록번호는 받지 않는다(저장하지 않은 데이터는 새지 않는다). */
export type CashReceiptIdentifierType = 'MOBILE' | 'CASH_RECEIPT_CARD' | 'BUSINESS_NUMBER';

export type CashReceiptStatus =
  | 'REQUESTED' | 'ISSUED' | 'CANCEL_REQUESTED' | 'CANCELED' | 'FAILED';

export interface CashReceipt {
  id: number;
  paymentId: number;
  orderId: number;
  purpose: CashReceiptPurpose;
  purposeLabel: string;
  identifierType: CashReceiptIdentifierType;
  /** 뒤 4 자리만 — 서버가 원문을 내려주지 않는다. */
  maskedIdentifier: string;
  totalAmount: number;
  supplyAmount: number;
  vatAmount: number;
  status: CashReceiptStatus;
  approvalNumber: string | null;
  failureReason: string | null;
  issuedAt: string | null;
}

export interface IssueCashReceiptRequest {
  purpose: CashReceiptPurpose;
  identifierType: CashReceiptIdentifierType;
  identifierValue: string;
}

/**
 * 현금영수증 — 계좌이체·가상계좌 결제 전용.
 *
 * 주문 기준 경로를 쓴다: 고객 화면이 들고 있는 식별자는 주문번호이고, 결제 id 는 노출되지 않는다.
 */
export const cashReceiptApi = {
  /** 발급 이력 조회. 없으면 204 → null. */
  getByOrder: async (orderId: number): Promise<CashReceipt | null> => {
    const response = await api.get<CashReceipt>(`/api/payments/by-order/${orderId}/cash-receipt`);
    return response.status === 204 ? null : response.data;
  },

  issueForOrder: async (orderId: number, request: IssueCashReceiptRequest): Promise<CashReceipt> => {
    const response = await api.post<CashReceipt>(
      `/api/payments/by-order/${orderId}/cash-receipt`, request);
    return response.data;
  },
};
