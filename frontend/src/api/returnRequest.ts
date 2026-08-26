import api from './axios';

/**
 * 반품·교환·취소 <b>신청 레코드</b> — order-service {@code OrderReturnRequestController} /
 * {@code AdminReturnRequestController}.
 *
 * <p>{@link orderWorkflowApi} 와 무엇이 다른가: 그쪽은 <b>주문 상태</b>를 민다(REFUND_REQUESTED 로
 * 옮긴다). 여기는 <b>신청이라는 사실</b>을 남긴다 — 사유 코드, 환불받을 계좌, 회수 송장, 교환
 * 재배송 송장. 상태 전이만으로는 "어느 택배로 돌려보냈는지"와 "어디로 송금해야 하는지"를 적을
 * 곳이 없어 전부 전화·메모로 흘러내린다.
 *
 * <p>신청을 내면 서버가 주문 상태도 함께 옮기므로, 화면은 이 API 하나만 부르면 된다.
 *
 * <p><b>계좌 번호는 마스킹된 값만 돌아온다</b>({@code refundAccountNumberMasked}). 목록이 계좌
 * 원문을 흘리면 신청 100 건을 부르는 것만으로 계좌 100 개가 나간다. 전체 번호가 필요한 곳은 실제로
 * 송금하는 사람뿐이고 그 경로는 이 API 가 아니다.
 */

export type ReturnRequestType = 'RETURN' | 'EXCHANGE' | 'CANCEL';

export type ReturnRequestStatusValue =
  | 'REQUESTED'
  | 'APPROVED'
  | 'COLLECTED'
  | 'COMPLETED'
  | 'REJECTED'
  | 'WITHDRAWN';

export const RETURN_REQUEST_TYPE_LABEL: Record<ReturnRequestType, string> = {
  RETURN: '반품',
  EXCHANGE: '교환',
  CANCEL: '취소',
};

export const RETURN_REQUEST_STATUS_LABEL: Record<ReturnRequestStatusValue, string> = {
  REQUESTED: '신청됨',
  APPROVED: '승인됨',
  COLLECTED: '회수 완료',
  COMPLETED: '처리 완료',
  REJECTED: '거절됨',
  WITHDRAWN: '철회됨',
};

/**
 * 사유 코드 표.
 *
 * <p>서버는 이 값을 <b>길이 40 이내의 문자열</b>로만 받고 enum 으로 좁히지 않는다. 사유는 정책이
 * 바뀔 때마다 늘어나는데, 그때마다 서버 배포를 기다려야 한다면 화면은 결국 "기타"에 전부 몰아넣는다.
 * 대신 표를 <b>여기 한 곳</b>에 둔다 — 고객 화면과 운영 대기열이 각자 라벨을 들고 있으면
 * 대기열에는 코드 원문이 뜨고, 운영자는 {@code WRONG_OPTION} 이 무엇인지 추측하게 된다.
 */
export const RETURN_REASON_LABEL: Record<string, string> = {
  CHANGE_MIND: '단순 변심',
  DEFECT: '상품 불량·파손',
  WRONG_ITEM: '다른 상품이 왔어요',
  WRONG_OPTION: '옵션을 잘못 선택했어요',
  DELIVERY_DELAY: '배송이 너무 늦어요',
  OTHER: '기타',
};

/** 코드 원문이 그대로 노출되지 않게 — 표에 없는 코드는 코드 자체를 보여준다. */
export const returnReasonLabel = (code: string): string => RETURN_REASON_LABEL[code] ?? code;

/**
 * 유형별로 고를 수 있는 사유.
 *
 * <p>교환에 "배송이 늦어요"를 두지 않는 이유: 늦어서 필요 없어진 주문은 교환이 아니라 반품이다.
 * 고를 수 있게 두면 회수·재배송을 한 바퀴 돌고 나서 결국 환불로 다시 온다.
 */
export const REASON_CODES_BY_TYPE: Record<ReturnRequestType, readonly string[]> = {
  CANCEL: ['CHANGE_MIND', 'WRONG_OPTION', 'DELIVERY_DELAY', 'OTHER'],
  RETURN: ['CHANGE_MIND', 'DEFECT', 'WRONG_ITEM', 'WRONG_OPTION', 'DELIVERY_DELAY', 'OTHER'],
  EXCHANGE: ['DEFECT', 'WRONG_ITEM', 'WRONG_OPTION', 'OTHER'],
};

/**
 * 환불 계좌 은행 — 금융결제원 표준 기관코드.
 *
 * <p>코드를 저장하고 <b>화면에는 항상 라벨로</b> 되돌려 보여준다({@link bankLabel}). 코드만
 * 남기면 송금하는 사람이 {@code 088} 을 은행 이름으로 옮기다가 틀리고, 틀린 것을 알아챌 자리가
 * 없다. 라벨로 보여주면 고객이 고른 은행과 다른 이름이 뜨는 순간 송금 전에 눈에 걸린다.
 *
 * <p>이 값은 뱅킹 API 로 나가지 않는다 — 운영자가 보고 손으로 이체하는 표시용이다.
 */
export const BANK_OPTIONS: readonly { code: string; name: string }[] = [
  { code: '004', name: 'KB국민은행' },
  { code: '088', name: '신한은행' },
  { code: '020', name: '우리은행' },
  { code: '081', name: '하나은행' },
  { code: '011', name: 'NH농협은행' },
  { code: '003', name: 'IBK기업은행' },
  { code: '090', name: '카카오뱅크' },
  { code: '092', name: '토스뱅크' },
  { code: '089', name: '케이뱅크' },
  { code: '071', name: '우체국' },
  { code: '007', name: '수협은행' },
  { code: '002', name: 'KDB산업은행' },
  { code: '023', name: 'SC제일은행' },
  { code: '027', name: '한국씨티은행' },
  { code: '031', name: 'iM뱅크(대구)' },
  { code: '032', name: '부산은행' },
  { code: '034', name: '광주은행' },
  { code: '035', name: '제주은행' },
  { code: '037', name: '전북은행' },
  { code: '039', name: '경남은행' },
  { code: '045', name: '새마을금고' },
  { code: '048', name: '신협' },
];

export const bankLabel = (code: string | null | undefined): string =>
  BANK_OPTIONS.find((b) => b.code === code)?.name ?? (code ?? '');

/** 아직 진행 중인 신청 — 주문당 하나만 열릴 수 있다(서버 부분 유니크 인덱스). */
export const OPEN_RETURN_STATUSES: readonly ReturnRequestStatusValue[] = [
  'REQUESTED',
  'APPROVED',
  'COLLECTED',
];

export const isOpenReturnRequest = (status: string): boolean =>
  (OPEN_RETURN_STATUSES as readonly string[]).includes(status);

export interface ReturnRequestResponse {
  id: number;
  orderId: number;
  userId: number;
  type: ReturnRequestType;
  status: ReturnRequestStatusValue;
  reasonCode: string;
  reasonDetail: string | null;
  refundBankCode: string | null;
  refundAccountNumberMasked: string | null;
  refundAccountHolder: string | null;
  /** 계좌로 송금해야 하는데 계좌가 아직 없다 — 이 값이 true 인 동안 환불은 실행되지 않는다. */
  awaitsRefundAccount: boolean;
  returnCarrier: string | null;
  returnTrackingNumber: string | null;
  exchangeCarrier: string | null;
  exchangeTrackingNumber: string | null;
  requestedBy: string;
  processedBy: string | null;
  rejectReason: string | null;
  requestedAt: string;
  approvedAt: string | null;
  collectedAt: string | null;
  exchangeShippedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

export interface SubmitReturnRequestPayload {
  type: ReturnRequestType;
  reasonCode: string;
  reasonDetail?: string | null;
  /** 무통장·가상계좌 주문에만 필요하다. 세 칸은 함께 채우거나 함께 비운다. */
  refundBankCode?: string | null;
  refundAccountNumber?: string | null;
  refundAccountHolder?: string | null;
}

export interface WaybillPayload {
  carrier: string;
  trackingNumber: string;
}

export interface RefundAccountPayload {
  bankCode: string;
  accountNumber: string;
  holderName: string;
}

export const returnRequestApi = {
  /** POST /orders/{orderId}/return-requests — 신청. 주문 상태도 서버가 함께 옮긴다. */
  submit: async (orderId: number, payload: SubmitReturnRequestPayload): Promise<ReturnRequestResponse> => {
    const response = await api.post<ReturnRequestResponse>(`/orders/${orderId}/return-requests`, payload);
    return response.data;
  },

  /** GET /orders/{orderId}/return-requests — 그 주문의 신청 이력(최근 것이 앞). */
  history: async (orderId: number): Promise<ReturnRequestResponse[]> => {
    const response = await api.get<ReturnRequestResponse[]>(`/orders/${orderId}/return-requests`);
    return response.data;
  },

  /** PUT /orders/{orderId}/return-requests/{id}/waybill — 고객이 반송한 택배사·송장. */
  registerWaybill: async (
    orderId: number, requestId: number, payload: WaybillPayload,
  ): Promise<ReturnRequestResponse> => {
    const response = await api.put<ReturnRequestResponse>(
      `/orders/${orderId}/return-requests/${requestId}/waybill`, payload);
    return response.data;
  },

  /** PUT /orders/{orderId}/return-requests/{id}/refund-account — 환불 계좌 등록·정정. */
  changeRefundAccount: async (
    orderId: number, requestId: number, payload: RefundAccountPayload,
  ): Promise<ReturnRequestResponse> => {
    const response = await api.put<ReturnRequestResponse>(
      `/orders/${orderId}/return-requests/${requestId}/refund-account`, payload);
    return response.data;
  },

  /** POST /orders/{orderId}/return-requests/{id}/withdraw — 주문 상태도 신청 직전으로 돌아간다. */
  withdraw: async (
    orderId: number, requestId: number, reason?: string,
  ): Promise<ReturnRequestResponse> => {
    const response = await api.post<ReturnRequestResponse>(
      `/orders/${orderId}/return-requests/${requestId}/withdraw`, { reason: reason ?? null });
    return response.data;
  },
};

/** 운영 콘솔 (ADMIN/MANAGER) — SecurityConfig 의 /admin/return-requests/** 매처로 게이트된다. */
export const adminReturnRequestApi = {
  /** GET /admin/return-requests — status 를 주지 않으면 열려 있는 신청 전부, 오래된 순. */
  queue: async (
    status?: readonly ReturnRequestStatusValue[], limit = 100,
  ): Promise<ReturnRequestResponse[]> => {
    const response = await api.get<ReturnRequestResponse[]>('/admin/return-requests', {
      params: { status: status && status.length > 0 ? [...status] : undefined, limit },
    });
    return response.data;
  },

  get: async (requestId: number): Promise<ReturnRequestResponse> => {
    const response = await api.get<ReturnRequestResponse>(`/admin/return-requests/${requestId}`);
    return response.data;
  },

  /** 승인 — 반품·교환은 회수를 기다린다. 출고 전 취소만 이 자리에서 환불까지 끝난다. */
  approve: async (requestId: number): Promise<ReturnRequestResponse> => {
    const response = await api.post<ReturnRequestResponse>(`/admin/return-requests/${requestId}/approve`);
    return response.data;
  },

  reject: async (requestId: number, reason: string): Promise<ReturnRequestResponse> => {
    const response = await api.post<ReturnRequestResponse>(
      `/admin/return-requests/${requestId}/reject`, { reason });
    return response.data;
  },

  /** 회수 확인 — 이 시점에 재고가 판매 가능으로 복귀한다. */
  collect: async (requestId: number): Promise<ReturnRequestResponse> => {
    const response = await api.post<ReturnRequestResponse>(`/admin/return-requests/${requestId}/collect`);
    return response.data;
  },

  /** 교환품 재배송 — 주문이 배송 준비로 돌아가고 신청이 끝난다. */
  shipExchange: async (requestId: number, payload: WaybillPayload): Promise<ReturnRequestResponse> => {
    const response = await api.post<ReturnRequestResponse>(
      `/admin/return-requests/${requestId}/exchange-shipment`, payload);
    return response.data;
  },

  /** 환불 실행 — 계좌 환불 대상인데 계좌가 비어 있으면 서버가 막는다. */
  refund: async (requestId: number): Promise<ReturnRequestResponse> => {
    const response = await api.post<ReturnRequestResponse>(`/admin/return-requests/${requestId}/refund`);
    return response.data;
  },

  changeRefundAccount: async (
    requestId: number, payload: RefundAccountPayload,
  ): Promise<ReturnRequestResponse> => {
    const response = await api.put<ReturnRequestResponse>(
      `/admin/return-requests/${requestId}/refund-account`, payload);
    return response.data;
  },
};
