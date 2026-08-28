import api from './axios';

/**
 * 파트너 콘솔(partner-service) API — 우리 몰에 입점한 기업이 <b>자기</b> 매출·주문만 보는 표면.
 *
 * <p><b>조직 번호를 보내는 자리가 없다.</b> 서버가 토큰에서 꺼낸 회원번호로 조직을 찾는다.
 * 레퍼런스 백오피스는 화면이 조직 번호를 파라미터로 실어 보냈고, 그러면 번호만 바꿔서 남의
 * 회사 매출을 여는 것이 막히지 않는다. 여기서는 화면이 그 번호를 아예 모른다 — 모르는 값은
 * 조작할 수 없다.
 *
 * <p><b>쓰기가 하나도 없다.</b> 이 서비스는 어떤 사실의 원본도 갖고 있지 않다. 주문·결제·상품·
 * 조직은 전부 다른 서비스가 이벤트로 흘려보낸 사본이고, 여기서 고치면 원본이 둘이 되어 다음
 * 이벤트가 조용히 덮는다. 그래서 서버에도 쓰기 매핑이 없고(ArchUnit 이 강제한다) 여기에도
 * post/put/delete 가 없다.
 *
 * <p><b>행의 기준은 주문이 아니라 결제다.</b> 이 서비스가 셀러를 알 수 있는 유일한 경로가
 * {@code payment.captured} 라서다. 결제되지 않은 주문은 목록에 나타나지 않는다 — 계약의
 * 한계이고, 화면도 그렇게 적는다.
 */

export type OrgType = 'SELLER' | 'CORPORATE';
export type MemberRole = 'OWNER' | 'MANAGER' | 'STAFF';
export type SellerTier = 'NORMAL' | 'VIP' | 'STRATEGIC';

export const MEMBER_ROLE_LABEL: Record<MemberRole, string> = {
  OWNER: '대표',
  MANAGER: '관리자',
  STAFF: '담당자',
};

export const SELLER_TIER_LABEL: Record<SellerTier, string> = {
  NORMAL: '일반',
  VIP: 'VIP',
  STRATEGIC: '전략',
};

export interface PartnerProfile {
  organizationId: number;
  organizationName: string;
  orgType: OrgType;
  sellerId: number | null;
  myRole: MemberRole;
  /**
   * 매출을 볼 수 있는 조직인가. <b>매출이 0 인 것과 매출 개념이 없는 것을 화면이 구분해야 한다</b> —
   * 둘 다 빈 표로 그리면 법인 고객은 자기 데이터가 유실됐다고 읽는다.
   */
  salesAvailable: boolean;
  /** 등급 이벤트가 아직 안 왔으면 null(=미확인)이다. 'NORMAL' 과 다르다. */
  currentTier: SellerTier | null;
  tierEffectiveFrom: string | null;
}

/**
 * 같은 조직의 구성원 한 명.
 *
 * <p>이름·이메일이 없는 것은 누락이 아니라 설계다 — 조직 이벤트가 숫자 {@code userId} 만 싣는다.
 * 없는 값을 회원 서비스에 물어 채우면 서비스 간 동기 호출이 생기고(이 저장소의 불변식 위반),
 * 개인정보가 이 화면에 들어오면 지금 없는 마스킹 통제가 필요해진다.
 */
export interface PartnerMember {
  membershipId: number;
  userId: number;
  role: MemberRole;
  joinedAt: string;
}

export interface SalesSummary {
  grossAmount: string;
  refundedAmount: string;
  /** gross − refunded. <b>음수가 될 수 있다</b> — 이번 달에 지난달 결제분이 환불되면 그렇다. */
  netAmount: string;
  /** 결제 건수(주문 건수가 아니다 — 한 주문이 분할 결제되면 2 로 센다). */
  orderCount: number;
}

export interface DailySales {
  date: string;
  grossAmount: string;
  refundedAmount: string;
  netAmount: string;
  orderCount: number;
}

export interface BestProduct {
  /** 결제는 왔는데 그 주문의 order.created 가 아직 안 왔으면 null 이다. 버리지 않는다(합이 안 맞는다). */
  productId: number | null;
  productName: string | null;
  /** 환불을 뺀 실매출. 총매출로 줄을 세우면 전량 환불된 상품이 1위에 앉는다. */
  netAmount: string;
  orderCount: number;
}

export interface PartnerDashboard {
  from: string;
  to: string;
  summary: SalesSummary;
  daily: DailySales[];
  bestProducts: BestProduct[];
  /** 결제시각이 이벤트에 없어 수신 시각으로 대체된 결제가 기간 안에 있는가. 각주로 알린다. */
  estimatedCaptureDates: boolean;
}

export interface PartnerOrder {
  orderId: number;
  paymentId: number;
  capturedAt: string;
  capturedAtEstimated: boolean;
  amount: string;
  refundedAmount: string;
  netAmount: string;
  paymentMethod: string | null;
  /** order.created 가 아직 안 왔으면 null 이다. 'CREATED' 로 채우면 취소 건이 정상으로 보인다. */
  orderStatus: string | null;
  productId: number | null;
  productName: string | null;
}

export interface PartnerOrderPage {
  content: PartnerOrder[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * CSV 내려받기 결과.
 *
 * <p>{@code truncated} 를 화면까지 들고 오는 것이 이 타입의 요점이다. 서버는 큰 기간을 자르되
 * 잘랐다는 사실을 응답 헤더로 알리는데, 클라이언트가 그 헤더를 안 읽으면 파일은 열리고 숫자도
 * 들어 있으므로 <b>틀렸다는 신호가 어디에도 남지 않는다</b>. 파트너는 그 CSV 를 전부라고 믿고
 * 회계에 쓴다.
 */
export interface OrderExportResult {
  blob: Blob;
  fileName: string;
  totalMatched: number;
  truncated: boolean;
}

export interface OrderFilter {
  from?: string | null;
  to?: string | null;
  orderId?: number | null;
}

/**
 * 쿼리스트링 — <b>앞의 {@code ?} 는 붙이지 않는다</b>. 호출부가 경로 리터럴에 직접 적는다.
 *
 * <p>이유가 문체가 아니라 게이트다. 화면-API 대조 게이트는 `${...}` 보간을 {@code *} 로 접은
 * 뒤 경로를 읽는데, {@code `/api/partner/dashboard${q}`} 처럼 보간이 경로에 <i>붙어</i> 있으면
 * {@code /api/partner/dashboard*} 가 되어 어느 엔드포인트와도 같지 않게 된다. 그러면 화면이
 * 멀쩡히 부르는 컨트롤러가 "화면 없음" 으로 집계된다 — 스캔이 0 이 되는 실패와 달리 조용히
 * 부채를 부풀리는 방향이라 오래 산다. {@code ?} 앞까지가 경로임을 게이트도 사람도 안다.
 */
const query = (filter: OrderFilter, extra: Record<string, string> = {}): string => {
  const params = new URLSearchParams(extra);
  if (filter.from) params.set('from', filter.from);
  if (filter.to) params.set('to', filter.to);
  if (filter.orderId !== null && filter.orderId !== undefined) {
    params.set('orderId', String(filter.orderId));
  }
  return params.toString();
};

/** Content-Disposition 의 파일명. 없으면 기본값 — 서버가 UTF-8'' 로 인코딩해 보낸다. */
const fileNameOf = (disposition: string, fallback: string): string => {
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  return match ? decodeURIComponent(match[1]) : fallback;
};

export const partnerApi = {
  // 경로는 전체 리터럴로 적는다 — 조각을 이어 붙이면 사람 눈에도, 저장소의 화면-API 대조
  // 게이트(api-screen-gate)에도 어떤 엔드포인트를 부르는지 보이지 않는다.
  /**
   * {@code skipAuthToast} — 이 호출의 403 은 사고가 아니라 <b>화면이 그리는 상태</b>다.
   * 입점 조직이 아닌 계정이 콘솔을 열면 서버가 403 NOT_A_PARTNER 를 주고, 화면은 "이 계정은
   * 입점 조직에 속해 있지 않습니다" 를 띄운다. 전역 인터셉터의 빨간 "접근 권한이 없습니다"
   * 토스트까지 겹치면 설명하는 화면 위에 사고처럼 보이는 경고가 하나 더 뜬다.
   */
  me: async (): Promise<PartnerProfile> =>
    (await api.get<PartnerProfile>('/api/partner/me', { skipAuthToast: true })).data,

  members: async (): Promise<PartnerMember[]> =>
    (await api.get<PartnerMember[]>('/api/partner/members')).data,

  dashboard: async (filter: OrderFilter = {}): Promise<PartnerDashboard> =>
    (await api.get<PartnerDashboard>(`/api/partner/dashboard?${query(filter)}`)).data,

  orders: async (filter: OrderFilter, page: number, size: number): Promise<PartnerOrderPage> =>
    (await api.get<PartnerOrderPage>(
      `/api/partner/orders?${query(filter, { page: String(page), size: String(size) })}`)).data,

  order: async (orderId: number): Promise<PartnerOrder> =>
    (await api.get<PartnerOrder>(`/api/partner/orders/${orderId}`)).data,

  exportOrders: async (filter: OrderFilter): Promise<OrderExportResult> => {
    const response = await api.get<Blob>(`/api/partner/exports/orders?${query(filter)}`,
      { responseType: 'blob' });
    const headers = response.headers as Record<string, unknown>;
    return {
      blob: response.data,
      fileName: fileNameOf(String(headers['content-disposition'] ?? ''), 'partner_orders.csv'),
      totalMatched: Number(headers['x-partner-export-total'] ?? 0),
      // 헤더가 없으면 <b>잘리지 않았다</b>가 아니라 "모른다"에 가깝지만, 서버가 항상 붙이므로
      // 없는 경우는 프록시가 헤더를 지운 때다. 그때 '잘렸다'로 겁주면 매번 경고가 뜬다.
      truncated: String(headers['x-partner-export-truncated'] ?? 'false') === 'true',
    };
  },
};

/** 금액 문자열(BigDecimal 직렬화)을 원화 표기로. 음수도 그대로 보인다 — 깎으면 정산과 어긋난다. */
export const formatMoney = (value: string): string =>
  `${Number(value).toLocaleString('ko-KR')}원`;

/**
 * 이 조직이 매출 화면을 볼 수 있는가 — 서버 판정의 사본이다.
 *
 * <p>{@code salesAvailable} 하나만 보면 되도록 서버가 이미 접어서 준다. 화면이
 * {@code orgType === 'SELLER'} 로 다시 판정하지 않는 이유는, 셀러 조직인데 셀러 ID 가 아직
 * 안 온 상태가 실제로 있기 때문이다(조직 이벤트와 셀러 연결 이벤트의 도착 순서는 보장되지 않는다).
 */
export const canSeeSales = (profile: PartnerProfile): boolean => profile.salesAvailable;
