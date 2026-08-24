import api from './axios';

/**
 * 환불 운영 조회 — order-service {@code AdminRefundController} · {@code RefundHistoryController}.
 *
 * <p><b>읽기 전용이다.</b> 서버에 운영자용 환불 재시도 API 가 없다 — 재시도는
 * {@code RefundRetryScheduler} 가 <b>원래 멱등 키로</b> 자동 재호출하고, 상한(5회)에 도달하면
 * {@code nextRetryAt} 을 비워 스케줄러 대상에서 빼 버린다. 그 순간부터가 사람 몫이다.
 *
 * <p>그래서 이 모듈에 "재시도" 함수를 두지 않았다. 고객용 환불 요청
 * ({@code PATCH /payments/{id}/refund})을 운영자 버튼으로 재활용하면 멱등 키 의미가 어긋나
 * 이중 환불을 만들 수 있다 — 없는 동작을 화면이 지어내지 않는다.
 */

export type RefundStatus = 'REQUESTED' | 'COMPLETED' | 'FAILED';

/** 자동 재시도 상한 — 도메인 상수 {@code Refund.MAX_RETRIES} 와 같은 값. */
export const REFUND_MAX_RETRIES = 5;

export interface AdminRefundItem {
  id: number;
  paymentId: number;
  amount: number;
  status: RefundStatus;
  retryCount: number;
  /**
   * 상한까지 실패해 스케줄러가 더는 손대지 않는 건 — <b>사람이 개입해야 하는 대상</b>.
   * 이 값이 false 인 FAILED 는 아직 자동으로 다시 시도된다(같은 FAILED 라도 성격이 다르다).
   */
  retryExhausted: boolean;
  /** 다음 자동 재시도 시각. 소진되면 서버가 비운다(null). */
  nextRetryAt: string | null;
  idempotencyKey: string | null;
  reason: string | null;
  requestedAt: string;
  completedAt: string | null;
}

export interface RefundHistoryItem {
  id: number;
  amount: number;
  status: RefundStatus;
  idempotencyKey: string | null;
  reason: string | null;
  requestedAt: string;
  completedAt: string | null;
}

export interface RefundHistory {
  paymentId: number;
  /** COMPLETED 건만 합산한 실제 환불액. */
  totalRefunded: number;
  refunds: RefundHistoryItem[];
}

export const refundAdminApi = {
  /** 상태별 환불 목록 (ADMIN·MANAGER — 서버가 게이트한다). */
  byStatus: async (status: RefundStatus): Promise<AdminRefundItem[]> =>
    (await api.get<AdminRefundItem[]>('/admin/refunds', { params: { status } })).data,

  /** 결제 한 건의 환불 시도 전체 — "몇 번 시도했고 무엇이 실제로 나갔나"를 본다. */
  historyOf: async (paymentId: number): Promise<RefundHistory> =>
    (await api.get<RefundHistory>(`/api/payments/${paymentId}/refunds`)).data,
};
