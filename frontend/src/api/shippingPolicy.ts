import api from './axios';

/**
 * 셀러 배송비 정책 API — `/admin/shipping-policies/**` (**ADMIN 전용**).
 *
 * <p>고객이 실제로 지불하는 금액을 바꾸는 설정이라 조회 콘솔과 달리 MANAGER 에게 열지 않는다
 * (서버 `SecurityConfig` 가 같은 경로를 ADMIN 으로 막는다 — 화면만 열면 죽은 링크가 된다).
 *
 * <p><b>세 가지 상태를 구분해서 다룬다</b>. 하나로 뭉개면 운영자가 배송비가 왜 그렇게
 * 붙는지 화면만 보고는 설명할 수 없다:
 *   ① <b>정책 없음</b>(목록에 행이 없음) — 그 셀러 주문에는 기본배송비가 부과되지 않는다(0 원).
 *   ② <b>임계 없음</b>(`freeThreshold === null`) — 금액과 무관하게 항상 부과한다.
 *   ③ <b>임계 0</b>(`freeThreshold === '0'`) — 항상 무료. ②와 정반대다.
 *
 * <p>금액은 숫자가 아니라 <b>문자열</b>로 다룬다. 서버가 BigDecimal 로 보내는 값을 number 로
 * 받으면 표시·재전송 과정에서 배정밀도 부동소수를 거치게 된다 — 금액에는 쓰지 않는 표현이다.
 */

export interface SellerShippingPolicy {
  sellerId: number;
  /** 기본배송비(원) */
  baseFee: string;
  /** 무료배송 임계. null 이면 무료배송 조건 없음 */
  freeThreshold: string | null;
}

export interface UpsertShippingPolicyRequest {
  baseFee: string;
  /** 생략·null 이면 "무료배송 조건 없음"으로 저장된다 */
  freeThreshold?: string | null;
}

const BASE = '/admin/shipping-policies';

/**
 * 서버가 실제로 보내는 모양. REST 응답의 {@code BigDecimal} 은 <b>JSON 숫자</b>다 —
 * 문자열 직렬화기(PlainStringBigDecimalSerializer)는 Outbox 이벤트에만 걸려 있고 REST 에는 없다.
 */
interface SellerShippingPolicyWire {
  sellerId: number;
  baseFee: number | string;
  freeThreshold: number | string | null;
}

/**
 * 경계에서 금액을 문자열로 고정한다.
 *
 * <p>이걸 하지 않으면 목록의 값이 숫자인 채로 폼 상태에 실려, 문자열을 기대하는 검증
 * (`value.trim()`)에서 터진다 — 실제로 '변경' 버튼이 화면을 하얗게 만들었다.
 * 타입 선언만 `string` 으로 두고 런타임을 확인하지 않으면 이런 어긋남은 조용히 남는다.
 */
const normalize = (raw: SellerShippingPolicyWire): SellerShippingPolicy => ({
  sellerId: raw.sellerId,
  baseFee: String(raw.baseFee),
  freeThreshold: raw.freeThreshold === null || raw.freeThreshold === undefined
    ? null
    : String(raw.freeThreshold),
});

/** 원 단위 금액 표시. 값이 없으면 '-' 가 아니라 호출부가 문맥에 맞는 문구를 고르게 한다. */
export const formatWon = (amount: string): string => {
  const n = Number(amount);
  return Number.isNaN(n) ? amount : `${n.toLocaleString('ko-KR')}원`;
};

/** 임계값을 사람 문장으로 — null/0 의 의미 차이가 목록에서 바로 읽혀야 한다. */
export const describeThreshold = (freeThreshold: string | null): string => {
  if (freeThreshold === null) return '무료배송 없음';
  return Number(freeThreshold) === 0 ? '항상 무료' : `${formatWon(freeThreshold)} 이상 무료`;
};

export const shippingPolicyApi = {
  /** 등록된 정책 전체(셀러 ID 오름차순). 정책이 없는 셀러는 애초에 행이 없다. */
  list: async (): Promise<SellerShippingPolicy[]> =>
    (await api.get<SellerShippingPolicyWire[]>(BASE)).data.map(normalize),

  /** 단건 조회 — 미등록이면 404 다. 호출부는 이 404 를 오류가 아니라 '미등록'으로 읽는다. */
  get: async (sellerId: number): Promise<SellerShippingPolicy> =>
    normalize((await api.get<SellerShippingPolicyWire>(`${BASE}/${sellerId}`)).data),

  /**
   * 등록·변경(셀러당 1 건 upsert).
   * 음수 금액은 서버 도메인이 400, 없는 셀러는 404 로 거절한다.
   */
  upsert: async (
    sellerId: number,
    body: UpsertShippingPolicyRequest
  ): Promise<SellerShippingPolicy> =>
    normalize((await api.put<SellerShippingPolicyWire>(`${BASE}/${sellerId}`, body)).data),
};
