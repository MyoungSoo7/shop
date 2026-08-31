import api from './axios';

/**
 * 셀러 콘솔(seller-service) API — 셀러가 <b>자기</b> 상품을 올리고 자기 주문을 처리하는 표면.
 *
 * <p><b>파트너 콘솔과 무엇이 다른가.</b> 파트너는 읽기만 한다(원본이 하나도 없다). 이쪽은
 * 원장을 하나 쥔다 — 상품 등록 신청서다. 그래서 여기에는 post/put 이 있고, 그 쓰기가 결국
 * 몰의 카탈로그에 상품을 만든다. 다만 <b>직접</b> 만들지는 않는다: 신청 → 심사 → 승인 →
 * (이벤트) → order-service 가 상품을 만든다. 화면이 이 시차를 감추면 안 된다.
 *
 * <p><b>셀러 번호를 보내는 자리가 없다.</b> 파트너 콘솔과 같은 이유다 — 서버가 토큰의
 * 회원번호로 조직을 찾는다. 화면이 자기 셀러 번호를 아예 모르므로 번호를 바꿔 남의 신청서를
 * 여는 것이 성립하지 않는다. 남의 신청번호를 넣으면 존재 여부도 드러나지 않고 404 다.
 *
 * <p><b>운영자 경로만 접두사가 다르다</b>({@code /api/seller/admin/**}). 대상이 "내 조직" 이
 * 아니라 전체라서 스코프로 좁힐 수가 없고, 그래서 경로 자체를 ROLE_ADMIN 으로 막는다.
 *
 * <p><b>금액은 number 다.</b> outbox 페이로드에서는 금액을 plain string 으로 직렬화하지만
 * (DATA-STANDARD N5), 그건 이벤트 계약의 규칙이고 REST 응답은 Spring 기본 Jackson 이 그리는
 * JSON 숫자다. 두 규칙을 한 이름으로 부르지 않는다.
 */

export type SubmissionStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';
export type SubmissionType = 'NEW' | 'UPDATE';
export type MemberRole = 'OWNER' | 'MANAGER' | 'STAFF';
export type OrgType = 'SELLER' | 'CORPORATE';

export const SUBMISSION_STATUS_LABEL: Record<SubmissionStatus, string> = {
  DRAFT: '작성 중',
  SUBMITTED: '심사 대기',
  APPROVED: '승인',
  REJECTED: '반려',
};

export const MEMBER_ROLE_LABEL: Record<MemberRole, string> = {
  OWNER: '대표',
  MANAGER: '관리자',
  STAFF: '담당자',
};

export interface SellerProfile {
  organizationId: number;
  organizationName: string;
  orgType: OrgType;
  /** 셀러 연결 이벤트가 아직 안 왔으면 null 이다. 조직은 있는데 셀러 번호가 없는 상태가 실제로 있다. */
  sellerId: number | null;
  myRole: MemberRole;
  /**
   * 심사에 올릴 수 있는 자격인가(STAFF 는 false). <b>버튼을 감추기 위한 값일 뿐</b>이고 실제
   * 차단은 서버가 매 요청마다 다시 한다 — 화면이 보내는 어떤 값도 권한 근거가 되지 않는다.
   * 화면이 역할 문자열을 보고 스스로 판정하지 않는 이유는 규칙이 두 벌이 되면 한쪽이 먼저
   * 낡기 때문이다.
   */
  canSubmit: boolean;
}

/**
 * 같은 조직의 구성원 한 명.
 *
 * <p>이름·이메일이 없는 것은 누락이 아니다 — 조직 이벤트가 숫자 {@code userId} 만 싣는다.
 * 없는 값을 회원 서비스에 물어 채우면 서비스 간 동기 호출이 생기고, 개인정보가 이 화면에
 * 들어오면 지금 없는 마스킹 통제가 필요해진다.
 */
export interface SellerMember {
  membershipId: number;
  userId: number;
  role: MemberRole;
  joinedAt: string;
}

export interface Submission {
  submissionId: number;
  sellerId: number;
  type: SubmissionType;
  /** 수정 신청이면 고칠 상품 번호. 신규면 null. */
  baseProductId: number | null;
  name: string;
  description: string | null;
  price: number;
  stock: number;
  category: string | null;
  imageUrl: string | null;
  displayVisible: boolean;
  status: SubmissionStatus;
  rejectReason: string | null;
  /** 카탈로그에 실린 뒤에야 채워진다. 승인 직후에는 null 이다. */
  productId: number | null;
  /**
   * 승인은 났는데 상품번호가 아직 안 돌아온 상태. 화면은 이걸 "승인" 과 <b>다르게</b> 그려야
   * 한다 — 같게 그리면 등록이 실패해 영영 상품이 안 생긴 건과, 방금 승인돼 몇 초 뒤 생길 건이
   * 화면에서 구분되지 않는다.
   */
  awaitingCatalog: boolean;
  createdByUserId: number;
  decidedByUserId: number | null;
  submittedAt: string | null;
  decidedAt: string | null;
}

export interface SubmissionPage {
  content: Submission[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SellerOrder {
  orderId: number;
  paymentId: number;
  capturedAt: string;
  /** 결제시각이 이벤트에 없어 수신 시각으로 대체된 행. 셀러는 이 날짜로 출고 기한을 센다. */
  capturedAtEstimated: boolean;
  amount: number;
  refundedAmount: number;
  netAmount: number;
  paymentMethod: string | null;
  /** order.created 가 아직 안 왔으면 null 이다. 'CREATED' 로 채우면 취소 건이 정상으로 보인다. */
  orderStatus: string | null;
  productId: number | null;
  productName: string | null;
  shipmentRegistered: boolean;
  carrier: string | null;
  trackingNumber: string | null;
  shipmentRequestedAt: string | null;
}

export interface SellerOrderPage {
  content: SellerOrder[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SubmissionInput {
  type?: SubmissionType;
  baseProductId?: number | null;
  name: string;
  description: string | null;
  price: number;
  stock: number;
  category: string | null;
  imageUrl: string | null;
  displayVisible: boolean;
}

export interface OrderFilter {
  from?: string | null;
  to?: string | null;
  orderId?: number | null;
  unshippedOnly?: boolean;
}

/**
 * 쿼리스트링 — <b>앞의 {@code ?} 는 붙이지 않는다</b>. 호출부가 경로 리터럴에 직접 적는다.
 *
 * <p>이유는 파트너 API 모듈과 같다: 화면-API 대조 게이트가 `${...}` 보간을 {@code *} 로 접기
 * 때문에, 보간이 경로에 <i>붙어</i> 있으면 어느 엔드포인트와도 같지 않게 되어 멀쩡히 부르는
 * 컨트롤러가 "화면 없음" 으로 집계된다. {@code ?} 앞까지가 경로임을 게이트도 사람도 안다.
 */
const orderQuery = (filter: OrderFilter, extra: Record<string, string> = {}): string => {
  const params = new URLSearchParams(extra);
  if (filter.from) params.set('from', filter.from);
  if (filter.to) params.set('to', filter.to);
  if (filter.orderId !== null && filter.orderId !== undefined) {
    params.set('orderId', String(filter.orderId));
  }
  if (filter.unshippedOnly) params.set('unshippedOnly', 'true');
  return params.toString();
};

export const sellerApi = {
  /**
   * {@code skipAuthToast} — 이 호출의 403 은 사고가 아니라 <b>화면이 그리는 상태</b>다.
   * 셀러 조직이 아닌 계정이 콘솔을 열면 서버가 403 {@code NOT_A_SELLER_MEMBER}(또는 조직은
   * 맞는데 파는 쪽이 아니면 422 {@code NOT_A_SELLER_ORG})를 주고, 화면이 그 문구를 그린다.
   * 전역 인터셉터의 빨간 토스트까지 겹치면 설명하는 화면 위에 사고처럼 보이는 경고가 하나 더 뜬다.
   */
  profile: async (): Promise<SellerProfile> =>
    (await api.get<SellerProfile>('/api/seller/profile', { skipAuthToast: true })).data,

  members: async (): Promise<SellerMember[]> =>
    (await api.get<SellerMember[]>('/api/seller/members')).data,

  submissions: async (status: SubmissionStatus | null, page: number, size: number): Promise<SubmissionPage> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status !== null) params.set('status', status);
    return (await api.get<SubmissionPage>(`/api/seller/products?${params.toString()}`)).data;
  },

  submission: async (submissionId: number): Promise<Submission> =>
    (await api.get<Submission>(`/api/seller/products/${submissionId}`)).data,

  createSubmission: async (input: SubmissionInput): Promise<Submission> =>
    (await api.post<Submission>('/api/seller/products', input)).data,

  updateSubmission: async (submissionId: number, input: SubmissionInput): Promise<Submission> =>
    (await api.put<Submission>(`/api/seller/products/${submissionId}`, input)).data,

  /** 심사에 올린다. 되돌리는 경로는 없다 — 반려돼야 다시 고칠 수 있다. */
  submitSubmission: async (submissionId: number): Promise<Submission> =>
    (await api.post<Submission>(`/api/seller/products/${submissionId}/submit`, {})).data,

  orders: async (filter: OrderFilter, page: number, size: number): Promise<SellerOrderPage> =>
    (await api.get<SellerOrderPage>(
      `/api/seller/orders?${orderQuery(filter, { page: String(page), size: String(size) })}`)).data,

  order: async (orderId: number): Promise<SellerOrder> =>
    (await api.get<SellerOrder>(`/api/seller/orders/${orderId}`)).data,

  /**
   * 송장 등록. 202 만 돌아오고 본문이 없다 — 여기서 무엇을 돌려줘도 그건 "접수했다" 는 뜻일
   * 뿐이고 실제 배송 전이는 order-service 가 이벤트를 받아 처리한다. 그 시차에 만들어진 값을
   * 화면이 확정으로 읽지 않도록 아무것도 돌려주지 않는다.
   */
  registerShipment: async (orderId: number, carrier: string, trackingNumber: string): Promise<void> => {
    await api.post(`/api/seller/orders/${orderId}/shipment`, { carrier, trackingNumber });
  },

  /** 운영자 — 심사 대기열. 상태 필터는 서버가 SUBMITTED 로 고정한다. */
  pendingSubmissions: async (page: number, size: number): Promise<SubmissionPage> =>
    (await api.get<SubmissionPage>(
      `/api/seller/admin/submissions?${new URLSearchParams({ page: String(page), size: String(size) })}`)).data,

  approveSubmission: async (submissionId: number): Promise<Submission> =>
    (await api.post<Submission>(`/api/seller/admin/submissions/${submissionId}/approve`, {})).data,

  /** 반려. 사유가 비어 있으면 서버가 400 이다 — 사유 없는 반려는 셀러에게 아무 정보도 주지 않는다. */
  rejectSubmission: async (submissionId: number, reason: string): Promise<Submission> =>
    (await api.post<Submission>(`/api/seller/admin/submissions/${submissionId}/reject`, { reason })).data,
};

/** 금액 표기. 음수도 그대로 보인다 — 깎으면 정산과 어긋난다. */
export const formatMoney = (value: number): string => `${value.toLocaleString('ko-KR')}원`;

/**
 * 신청서 한 건의 <b>표시용</b> 상태 — 원장의 status 하나로는 부족해서 화면이 한 겹 더 나눈다.
 *
 * <p>{@code APPROVED} 인데 상품번호가 없는 상태를 '승인' 으로 그리면, 등록이 실패해 영영
 * 상품이 안 생긴 건과 방금 승인돼 몇 초 뒤 생길 건이 같은 글자로 보인다. 둘을 가르는 것이
 * 이 함수의 전부다.
 */
export const displayStatus = (submission: Submission): string => {
  if (submission.status === 'APPROVED' && submission.awaitingCatalog) return '등록 처리 중';
  if (submission.status === 'APPROVED') return '판매중';
  return SUBMISSION_STATUS_LABEL[submission.status];
};
