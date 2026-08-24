import api from './axios';

/**
 * 텐더 결제 — 포인트·상품권·카드·가상계좌를 <b>한 결제 안에서</b> 나눠 내는 경로.
 *
 * <p>경로가 {@code /payments/split} 인 것은 이력이다. 처음엔 "분할결제" 전용이었지만 지금은
 * 지불수단 1 개(포인트 전액 결제 등)도 받는다 — 일반 결제 경로는 지불수단을 모델링하지 않아
 * 포인트·상품권 원장의 차감·복원이 걸릴 자리가 없기 때문이다.
 */

/** 백엔드 {@code TenderType} 중 화면이 제안하는 수단. */
export type TenderType =
  | 'POINT'
  | 'GIFT_CARD'
  | 'CARD'
  | 'BANK_TRANSFER'
  | 'VIRTUAL_ACCOUNT';

/** 돈이 나중에 들어오는 수단 — 이게 섞이면 결제가 그 자리에서 확정되지 않는다. */
export const AWAITS_DEPOSIT: ReadonlySet<TenderType> = new Set<TenderType>([
  'BANK_TRANSFER',
  'VIRTUAL_ACCOUNT',
]);

/** 내부 잔액 수단 — 외부 PG 를 거치지 않고 우리 원장에서 빠진다. */
export const INTERNAL_BALANCE: ReadonlySet<TenderType> = new Set<TenderType>([
  'POINT',
  'GIFT_CARD',
]);

export interface TenderLine {
  type: TenderType;
  amount: number;
}

export interface TenderView {
  id: number;
  type: TenderType;
  amount: number;
  refundedAmount: number;
  refundableAmount: number;
  pgTransactionId: string | null;
  status: string;
  sequence: number;
}

export interface TenderPaymentView {
  payment: {
    id: number;
    orderId: number;
    amount: number;
    refundedAmount: number;
    /** READY 면 입금 대기, CAPTURED 면 확정. */
    status: string;
    paymentMethod: string;
    isSplit: boolean;
  };
  tenders: TenderView[];
}

export const tenderPaymentApi = {
  /**
   * 텐더 결제 생성. 합계가 주문 금액과 <b>정확히</b> 일치해야 서버가 받는다.
   *
   * <p>가상계좌·무통장이 섞이면 응답의 결제 상태가 {@code READY} 로 온다 — 승인만 됐고 아직
   * 확정되지 않았다는 뜻이다. 이때 포인트·상품권은 차감이 아니라 <b>선점</b>돼 있다.
   */
  create: async (orderId: number, tenders: TenderLine[]): Promise<TenderPaymentView> =>
    (await api.post<TenderPaymentView>('/payments/split', { orderId, tenders })).data,

  /**
   * 입금 확인 — 실제로는 PG 입금 통보가 부르는 자리다. 연동이 붙기 전까지 화면에서 눌러
   * 확정할 수 있게 둔다. 같은 통보가 여러 번 와도 안전하다(서버가 멱등).
   */
  confirmDeposit: async (paymentId: number): Promise<TenderPaymentView> =>
    (await api.post<TenderPaymentView>(`/payments/split/${paymentId}/confirm-deposit`)).data,
};
