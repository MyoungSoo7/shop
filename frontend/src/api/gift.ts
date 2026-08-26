import api from './axios';

/**
 * 선물 주문 — order-service {@code OrderGiftController} / {@code GiftClaimController}.
 *
 * <p><b>왜 생겼나.</b> 지금까지 누군가에게 물건을 보내려면 <b>그 사람의 집 주소를 알아야</b> 했다.
 * 그래서 실무에서는 "주소 좀 알려줘"를 메신저로 묻고, 받은 주소를 주문서에 옮겨 적었다. 그 순간
 * 주소는 두 사람의 대화창과 주문서에 각각 한 벌씩 남고, 오타가 나도 어느 쪽이 맞는지 판정할
 * 근거가 없다. 무엇보다 <b>주기 싫은 주소를 줘야 선물을 받을 수 있었다.</b>
 *
 * <p>여기서는 보내는 사람이 <b>휴대폰 번호만</b> 알면 된다. 받는 사람이 링크로 들어와 본인확인을
 * 하고 자기 주소를 직접 넣는다.
 *
 * <h3>두 묶음이 나뉘어 있는 이유</h3>
 * <p>{@link giftApi} 는 로그인한 <b>보내는 사람</b>이 부르고, {@link giftClaimApi} 는 로그인하지 않은
 * <b>받는 사람</b>이 부른다. 서버에서도 경로 접두사부터 갈라져 있다({@code /orders/**} 는 인증 필요,
 * {@code /gift-claims/**} 만 열려 있다). 한 파일에 두되 절대 섞지 않는다 — 받는 사람 화면이 실수로
 * 보내는 사람 API 를 부르면 401 로 죽고, 그 반대는 열려 있어야 할 화면에 로그인을 요구하게 된다.
 *
 * <h3>평문 링크는 여기로 오지 않는다</h3>
 * <p>{@link SentGiftResponse} 에 토큰이 없다. 링크는 서버가 받는 사람 번호로 직접 보내고, 저장소에는
 * 해시만 남는다. 화면·로그·클립보드에 열쇠를 한 벌 더 뿌리지 않기 위해서다. 발송이 실패했으면
 * {@code linkDelivered=false} 가 오므로 화면은 <b>재발송을 권해야 한다</b> — 이 값을 무시하면 결제는
 * 끝났는데 링크는 아무 데도 안 간 선물이 아무도 모르는 채 유효기간을 넘긴다.
 */

export type GiftClaimStatusValue = 'PENDING' | 'VERIFIED' | 'CLAIMED' | 'EXPIRED' | 'CANCELED';

export const GIFT_CLAIM_STATUS_LABEL: Record<GiftClaimStatusValue, string> = {
  PENDING: '수령 대기',
  VERIFIED: '본인확인 완료',
  CLAIMED: '배송지 입력 완료',
  EXPIRED: '기간 만료',
  CANCELED: '회수됨',
};

/** 받는 사람이 아직 무언가 할 수 있는 상태인지 — 서버의 {@code GiftClaimStatus#isOpen} 과 같은 집합. */
export const OPEN_GIFT_STATUSES: readonly GiftClaimStatusValue[] = ['PENDING', 'VERIFIED'];

export const isOpenGiftClaim = (status: string): boolean =>
  (OPEN_GIFT_STATUSES as readonly string[]).includes(status);

export interface GiftLineRequest {
  productId: number;
  variantId?: number | null;
  quantity: number;
}

export interface SendGiftPayload {
  userId: number;
  lines: GiftLineRequest[];
  couponCode?: string | null;
  recipientName: string;
  /** 이 번호로 링크와 인증번호가 간다. 배송지는 받지 않는다 — 받는 사람이 직접 낸다. */
  recipientPhone: string;
  message?: string | null;
}

export interface SentGiftResponse {
  orderId: number;
  giftClaimId: number;
  status: GiftClaimStatusValue;
  maskedRecipientPhone: string;
  expiresAt: string;
  /** false 여도 주문은 성립했다. 화면은 이 값을 보고 재발송을 권해야 한다. */
  linkDelivered: boolean;
}

export interface GiftStatusResponse {
  orderId: number;
  giftClaimId: number;
  status: GiftClaimStatusValue;
  recipientName: string;
  maskedRecipientPhone: string;
  expiresAt: string;
  verifiedAt: string | null;
  claimedAt: string | null;
}

/** 보내는 사람 (로그인 필요). */
export const giftApi = {
  /** POST /orders/gifts — 선물 주문 생성 + 링크 발송. 일반 다건 주문과 같은 멱등 규칙. */
  send: async (payload: SendGiftPayload, idempotencyKey?: string): Promise<SentGiftResponse> => {
    const response = await api.post<SentGiftResponse>('/orders/gifts', payload, {
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    });
    return response.data;
  },

  /** GET /orders/{orderId}/gift — 받는 사람이 어디까지 왔는지. */
  status: async (orderId: number): Promise<GiftStatusResponse> => {
    const response = await api.get<GiftStatusResponse>(`/orders/${orderId}/gift`);
    return response.data;
  },

  /**
   * POST /orders/{orderId}/gift/resend — <b>새 토큰</b>으로 다시 보낸다.
   *
   * <p>같은 링크를 다시 보내는 것이 아니다. 평문 토큰은 발급 순간에만 존재하므로 애초에 불가능하고,
   * 재발송을 누르는 가장 흔한 이유가 "번호를 잘못 적었다"라서 옛 링크를 살려 두는 편이 위험하다.
   */
  resend: async (orderId: number): Promise<{ linkDelivered: boolean }> => {
    const response = await api.post<{ linkDelivered: boolean }>(`/orders/${orderId}/gift/resend`);
    return response.data;
  },

  /** POST /orders/{orderId}/gift/cancel — 링크만 닫는다. 결제 취소는 반품·취소 신청 경로다. */
  cancel: async (orderId: number): Promise<GiftStatusResponse> => {
    const response = await api.post<GiftStatusResponse>(`/orders/${orderId}/gift/cancel`);
    return response.data;
  },
};

export interface GiftViewItem {
  productName: string;
  quantity: number;
}

/**
 * 받는 사람에게 보이는 것 전부.
 *
 * <p><b>금액이 없다.</b> 인가가 링크 토큰 하나에 걸려 있어 나가는 값이 최소한이어야 하고,
 * 받는 사람에게 선물 가격을 보여 줄 이유도 없다.
 */
export interface GiftViewResponse {
  orderId: number;
  status: GiftClaimStatusValue;
  /** 지금 무언가 할 수 있는가 — 만료·종단이면 false. 화면 안내 문구의 근거. */
  actionable: boolean;
  recipientName: string;
  /** 인증번호가 어디로 가는지 확인시켜 주되 온전한 번호는 주지 않는다. */
  maskedPhone: string;
  message: string | null;
  expiresAt: string;
  items: GiftViewItem[];
}

export interface GiftAddressPayload {
  /** 비우면 선물에 적힌 받는 사람 이름을 쓴다 — 사무실 등 다른 이름으로 받을 때만 채운다. */
  recipientName?: string | null;
  phone: string;
  postalCode: string;
  address1: string;
  address2?: string | null;
  deliveryMemo?: string | null;
}

/**
 * 받는 사람 (비로그인).
 *
 * <p>모든 호출이 토큰 하나만 받는다. 회원가입을 요구하면 "주소를 주기 싫어서" 대신 "가입하기
 * 싫어서" 선물을 못 받게 될 뿐이라 문제가 그대로다.
 */
export const giftClaimApi = {
  /** GET /gift-claims/{token} — 화면의 재료. 상태 변화는 없다. */
  view: async (token: string): Promise<GiftViewResponse> => {
    const response = await api.get<GiftViewResponse>(`/gift-claims/${encodeURIComponent(token)}`);
    return response.data;
  },

  /** POST /gift-claims/{token}/code — 선물에 적힌 번호로 6자리를 보낸다. 이전 번호는 즉시 무효. */
  requestCode: async (token: string): Promise<void> => {
    await api.post(`/gift-claims/${encodeURIComponent(token)}/code`);
  },

  /** POST /gift-claims/{token}/verify — 틀리면 남은 시도 횟수가 에러 메시지에 담겨 온다. */
  verify: async (token: string, code: string): Promise<void> => {
    await api.post(`/gift-claims/${encodeURIComponent(token)}/verify`, { code });
  },

  /** POST /gift-claims/{token}/address — 이 호출로 주문에 배송지가 붙고 배송이 시작된다. */
  submitAddress: async (token: string, payload: GiftAddressPayload): Promise<void> => {
    await api.post(`/gift-claims/${encodeURIComponent(token)}/address`, payload);
  },
};
