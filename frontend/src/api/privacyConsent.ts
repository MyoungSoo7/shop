import api from './axios';

/**
 * 주문 시점 개인정보 동의 — order-service {@code PrivacyConsentController} /
 * {@code AdminPrivacyConsentController}.
 *
 * <p><b>왜 생겼나.</b> 그전까지 결제 화면에는 동의 체크박스가 아예 없었다. 그런데 주문 한 건이
 * 성립하면 이름·전화번호·주소가 <b>택배사로 넘어간다</b> — 제3자 제공이다. 개인정보 보호법
 * 제17조는 제공받는 자·제공 목적·제공 항목·보유 기간 넷을 알리고 동의를 받으라고 하는데,
 * 받은 적이 없으니 보여 준 문장도 없고 남은 기록도 없었다. 분쟁이 나면 "동의를 받았다"를
 * 증명할 방법이 없는 상태였다는 뜻이다.
 *
 * <h3>왜 문안을 화면이 들고 있지 않은가</h3>
 * <p>동의 문안을 프론트 상수로 박아 두면, 문장을 고친 순간 <b>이미 받아 둔 동의</b>가 무엇에 대한
 * 동의였는지 알 수 없게 된다. 그래서 문안은 서버가 버전과 함께 내려주고({@link privacyConsentApi.terms}),
 * 주문 요청에는 <b>내려받은 그 버전</b>을 그대로 실어 보낸다. 그 사이 문안이 바뀌었으면 주문이
 * 409 로 거절된다 — 사용자가 읽은 문장과 서버가 기록할 문장이 다른 채로 주문이 성립하지 않게
 * 하려는 것이다. 이때 화면이 할 일은 입력값 교정 안내가 아니라 <b>문안을 다시 받아 다시 보여
 * 주는 것</b>이다({@link isStaleTermsError} 로 그 둘을 가른다).
 *
 * <h3>거절도 기록이다</h3>
 * <p>선택 항목을 체크하지 않았어도 항목 자체는 보낸다({@code agreed: false}). "물었고 거절했다"와
 * "묻지 않았다"는 다른 사실이고, 나중에 마케팅 발송의 근거를 따질 때 필요한 것은 전자다.
 * 반대로 안 보낸 항목을 서버가 "거절"로 지어내지는 않는다.
 */

export type ConsentTypeValue =
  | 'COLLECTION_USE'
  | 'THIRD_PARTY_PROVISION'
  | 'PAYMENT_AGENCY'
  | 'MARKETING';

export const CONSENT_TYPE_LABEL: Record<ConsentTypeValue, string> = {
  COLLECTION_USE: '수집·이용',
  THIRD_PARTY_PROVISION: '제3자 제공',
  PAYMENT_AGENCY: '결제대행',
  MARKETING: '광고성 정보 수신',
};

/** 서버가 내려주는 현행 문안 한 건. 필수 항목이 앞에 온다. */
export interface PrivacyConsentTerms {
  code: string;
  version: number;
  consentType: ConsentTypeValue;
  title: string;
  /** 제공받는 자. 제3자 제공이 아닌 문안에서는 null 이다. */
  recipient: string | null;
  purpose: string;
  providedItems: string;
  retention: string;
  /** 전문. 접었다 펴는 그 문장이라 목록 응답에 함께 온다 — 따로 부르지 않는다. */
  body: string;
  required: boolean;
  effectiveFrom: string;
}

/** 주문 요청에 실어 보내는 체크 결과 한 건. */
export interface ConsentAcceptance {
  termsCode: string;
  /** 화면이 실제로 보여 준 문안의 버전. 서버 값이 다르면 주문이 409 로 거절된다. */
  termsVersion: number;
  agreed: boolean;
}

/** 지난 주문에서 무엇에 동의했는지. 접속지(IP)는 여기에 오지 않는다 — 운영자 경로에만 있다. */
export interface OrderPrivacyConsent {
  termsCode: string;
  termsVersion: number;
  consentType: ConsentTypeValue;
  agreed: boolean;
  recipient: string | null;
  purpose: string;
  providedItems: string;
  retention: string;
  agreedAt: string;
  /**
   * 동의 당시 문안이 지금도 같은가.
   *
   * <p>{@code false} 는 버전을 올리지 않고 문장을 고쳤다는 뜻이다. 화면에서 지우지 않고 그대로
   * 표시한다 — 감추면 그 사실을 아무도 모르게 되고, 그게 이 칸이 존재하는 이유다.
   */
  bodyUnchanged: boolean;
}

/** 운영자 응답 — 고객 응답에 없는 주문·동의자·접속지가 붙는다. */
export interface AdminOrderPrivacyConsent extends OrderPrivacyConsent {
  orderId: number;
  userId: number;
  /** 프록시 뒤에서 관찰한 값이라 보조 증거다. 이것만으로 사람을 특정하지 않는다. */
  ipAddress: string | null;
}

/**
 * 필수 항목이 전부 체크됐는가 — 주문 버튼의 활성 조건.
 *
 * <p>서버도 같은 판정을 하고 거절할 수 있다. 화면에서 한 번 더 보는 것은 막기 위해서가 아니라,
 * 결제 직전까지 갔다가 400 을 받는 흐름을 피하기 위해서다.
 */
export const allRequiredAgreed = (
  terms: PrivacyConsentTerms[],
  agreed: Record<string, boolean>,
): boolean => terms.filter((t) => t.required).every((t) => agreed[t.code] === true);

/**
 * 체크 상태를 주문 요청 형태로 바꾼다.
 *
 * <p>체크하지 않은 항목도 {@code agreed: false} 로 함께 나간다 — 위 "거절도 기록이다" 참조.
 */
export const toAcceptances = (
  terms: PrivacyConsentTerms[],
  agreed: Record<string, boolean>,
): ConsentAcceptance[] =>
  terms.map((t) => ({
    termsCode: t.code,
    termsVersion: t.version,
    agreed: agreed[t.code] === true,
  }));

/**
 * 이 실패가 "문안이 낡았다"인가.
 *
 * <p>서버가 400(체크하라)과 409(다시 받아라)를 나눠 주는 이유가 여기서 쓰인다. 409 를 400 처럼
 * 다뤄 "필수 항목에 동의해주세요"라고 안내하면, 이미 체크한 사용자는 무엇을 더 눌러야 하는지
 * 알 수 없어 화면에서 빠져나올 수 없다.
 */
export const isStaleTermsError = (error: unknown): boolean =>
  (error as { response?: { status?: number } })?.response?.status === 409;

export const privacyConsentApi = {
  /** GET /orders/consent-terms — 결제 화면이 보여 줘야 할 현행 문안. 필수가 앞에 온다. */
  terms: async (): Promise<PrivacyConsentTerms[]> => {
    const response = await api.get<PrivacyConsentTerms[]>('/orders/consent-terms');
    return response.data;
  },

  /** GET /orders/{orderId}/privacy-consents — 이 주문에서 무엇에 동의했는지. */
  ofOrder: async (orderId: number): Promise<OrderPrivacyConsent[]> => {
    const response = await api.get<OrderPrivacyConsent[]>(`/orders/${orderId}/privacy-consents`);
    return response.data;
  },
};

export const adminPrivacyConsentApi = {
  /**
   * GET /admin/privacy-consents?userId= — 한 사람의 동의 이력.
   *
   * <p>정보주체가 "내가 무엇에 동의했는지 보여 달라"고 요구할 때 답하는 경로다(열람 요구권).
   */
  ofUser: async (userId: number, limit = 100): Promise<AdminOrderPrivacyConsent[]> => {
    const response = await api.get<AdminOrderPrivacyConsent[]>('/admin/privacy-consents', {
      params: { userId, limit },
    });
    return response.data;
  },

  /**
   * GET /admin/privacy-consents?termsCode=&termsVersion= — 특정 문안 버전으로 동의한 이력.
   *
   * <p>문안을 고친 뒤 "옛 버전으로 동의한 사람이 남아 있는가"를 세는 축이다. 0 이 아니면 그
   * 사람들에게는 <b>지금 문안에 대한 동의가 없다</b> — 재동의를 받아야 한다는 뜻이다.
   */
  ofTermsVersion: async (
    termsCode: string,
    termsVersion: number,
    limit = 100,
  ): Promise<AdminOrderPrivacyConsent[]> => {
    const response = await api.get<AdminOrderPrivacyConsent[]>('/admin/privacy-consents', {
      params: { termsCode, termsVersion, limit },
    });
    return response.data;
  },
};
