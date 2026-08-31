import type { MenuArea, MenuNodeType, NavMenuNode } from '@/api/menu';

/**
 * 메뉴 트리 빌드타임 스냅샷 — `GET /api/menus/me` 가 실패했을 때만 쓰는 폴백이다.
 *
 * <p><b>왜 필요한가</b>: 네비게이션을 서버 응답으로 그리게 되면, 그 호출 하나가 실패할 때
 * 앱 전체가 "링크 없는 화면"이 된다. 사용자는 로그인은 됐는데 아무 데도 못 가는 상태가 된다.
 * 그래서 서버가 답하지 못할 때 최소한 이동은 가능하도록 마지막으로 알려진 트리를 들고 있는다.
 *
 * <p><b>정본은 여기가 아니다</b>: 정본은 order-service 의 메뉴 시드 마이그레이션이고
 * (`V20260813100000__menu_area_permission.sql` 이후의 `V*__menu_*.sql` 누적), 이 파일은 그 사본이다.
 * 둘이 어긋나면 `scripts/harness/test/menu-route-gate.test.mjs` 가 CI 에서 빌드를 깬다.
 *
 * <p><b>서버 필터링과의 차이</b>: 서버는 역할 + 권한(permission)으로 거른다. 폴백은 역할까지만
 * 본다 — 권한을 가진 역할은 현재 ADMIN 뿐이고 ADMIN 은 전 권한 보유자라, 역할만으로 같은
 * 결과가 나온다. 어차피 폴백은 "서버가 죽었을 때의 근사치"이고, 실제 인가는 각 API 가 한다.
 */
export interface FallbackMenuNode {
  /**
   * <b>트리 전체에서</b> 유일해야 한다. 최상위와 남의 그룹 자식까지 통틀어서다. 서버가 주는
   * 실제 메뉴 id 와 섞이지 않도록 전부 음수를 쓴다.
   *
   * <p>한 번 겹친 적이 있다: 최상위 '반품·교환'이 시스템 그룹의 자식 '수강 신청'과 같은 -122 를
   * 집었다. 새 화면은 대개 그룹 자식으로 들어가 뒷번호가 계속 늘어나므로, 최상위에 번호를
   * 새로 줄 때 "지금 안 쓰는 것 같은" 뒷번호를 집으면 이렇게 된다. 앞의 빈 번호부터 채운다.
   *
   * <p>지금은 겹쳐도 화면이 곧바로 깨지지는 않는다 — 상단 네비의 활성 표시는 트레일의 <i>뿌리</i>
   * id 만 보기 때문이다(`Layout.tsx`). 그래서 눈으로는 안 잡힌다. `menuFallbackParity.test.ts` 의
   * 유일성 검사가 대신 잡는다.
   */
  id: number;
  name: string;
  shortName?: string;
  path: string | null;
  icon: string | null;
  description: string | null;
  area: MenuArea;
  type: MenuNodeType;
  /** 접근 허용 역할. null 이면 제한 없음 */
  roles: string[] | null;
  children?: FallbackMenuNode[];
}

export const FALLBACK_MENUS: FallbackMenuNode[] = [
  {
    id: -1, name: '대시보드', path: '/admin', icon: '📊', description: null,
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
  },
  {
    id: -21, name: '상품관리', path: '/product', icon: '📦', description: '상품 · 재고 관리',
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
  },
  {
    id: -3, name: '배송', path: '/admin/shipping', icon: '🚚', description: null,
    area: 'BACKOFFICE', type: 'GROUP', roles: ['ADMIN', 'MANAGER'],
    children: [
      { id: -31, name: '배송 관리', path: '/admin/shipping', icon: '🚚', description: '주문별 배송 생성 · 출고 · 상태 전이', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      // 배송비는 고객이 실제로 지불하는 금액이라 서버가 ADMIN 으로 막는다 — 메뉴도 같은 등급이다.
      { id: -32, name: '배송비 정책', path: '/admin/shipping/policies', icon: '💵', description: '셀러 기본배송비 · 무료배송 임계', area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN'] },
    ],
  },
  {
    id: -4, name: '승인', path: '/admin/approvals', icon: '✅', description: null,
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
  },
  {
    // '승인' 의 자식이 아니라 형제다 — '승인' 은 ITEM 이라 자식을 붙이려면 GROUP 으로 바꿔야 하고,
    // 그러면 지금 그 링크로 들어가는 취소·환불 승인 큐가 링크가 아니게 된다.
    id: -5, name: '반품·교환', path: '/admin/approvals/returns', icon: '🔁',
    description: '승인 · 회수 확인 · 환불 · 교환 재배송',
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
  },
  {
    // 반품·교환과 같은 이유로 '승인' 의 형제다. 문의 응대도 CS 업무라 서버가 MANAGER 까지
    // 연다(/admin/inquiries/** 매처) — 메뉴도 같은 등급이어야 눌러도 되돌려보내는 죽은 링크가
    // 생기지 않는다.
    id: -6, name: '문의 응대', path: '/admin/approvals/inquiries', icon: '💬',
    description: '상품 문의 · 주문 문의 · 1:1 문의 답변',
    area: 'BACKOFFICE', type: 'ITEM', roles: ['ADMIN', 'MANAGER'],
  },
  {
    id: -7, name: '시스템 관리', shortName: '시스템', path: '/admin/system/menus', icon: '⚙️',
    description: 'System Administration', area: 'SYSTEM', type: 'GROUP', roles: ['ADMIN'],
    children: [
      { id: -81, name: '메뉴 관리', path: '/admin/system/menus', icon: '🗂️', description: '네비게이션 메뉴 트리', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -82, name: '공통코드 관리', path: '/admin/system/codes', icon: '🏷️', description: '코드 그룹 / 항목', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -83, name: 'RBAC 관리', path: '/admin/system/rbac', icon: '🔐', description: '역할 · 권한 매트릭스', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -84, name: '이커머스 카테고리', path: '/admin/system/ecommerce-categories', icon: '📁', description: '상품 카테고리 트리', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -86, name: '진열 편성', path: '/admin/system/display-sections', icon: '🎪', description: '기획전 · 메인 진열 편성', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -87, name: '옵션 카탈로그', path: '/admin/system/option-catalog', icon: '🎛️', description: '표준 옵션 축 · 값 관리', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -85, name: '운영관리', path: '/admin/system/operation', icon: '🖥️', description: '인시던트 관제 콘솔', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -89, name: '게시판 관리', path: '/admin/system/boards', icon: '📋', description: '게시판 생성 · 스킨 · 권한 정책', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -90, name: '교육 관리', path: '/admin/system/education', icon: '🎓', description: '교육 과정 · 강의 콘텐츠', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -91, name: '포인트 운영', path: '/admin/system/points', icon: '🪙', description: '수기 지급 · 유효기간 소멸', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -92, name: '기프트카드 운영', path: '/admin/system/gift-cards', icon: '🎁', description: '상품권 발행 · 유효기간 소멸', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -93, name: '감사 로그', path: '/admin/system/audit-logs', icon: '🔎', description: '조작 이력 조회', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -94, name: '회원 관리', path: '/admin/system/members', icon: '👤', description: '회원 검색 · 승인 · 역할 변경', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      // 조직·멤버십은 회원 관리 바로 뒤 — 개인(회원)을 본 다음 그 사람이 속한 조직으로 이어진다.
      { id: -116, name: '조직 · 멤버십', path: '/admin/system/organizations', icon: '🏢', description: '셀러/기업 조직 · 초대 · 역할', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -95, name: '리뷰 관리', path: '/admin/system/reviews', icon: '⭐', description: '신고 리뷰 블라인드 · 해제', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -96, name: '쿠폰 운영', path: '/admin/system/coupons', icon: '🎟️', description: '쿠폰 검색 · 중단/재개 · 사용 내역', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      // 환불 운영은 서버가 ADMIN·MANAGER 로 막는다 — 시스템 그룹 안에서 유일하게 등급이 낮다.
      { id: -117, name: '환불 운영', path: '/admin/system/refunds', icon: '↩️', description: '재시도 소진 건 · 결제별 환불 이력', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      { id: -112, name: '셀러 등급', path: '/admin/system/seller-tiers', icon: '🏅', description: '등급 재산정 · 관리자 지정 · 캐시 정합', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -118, name: '권한 계정', path: '/admin/system/operators', icon: '🛡️', description: '조작 권한 계정 명부 · 미사용 회수 · 잠금 해제', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -119, name: '지표 추이', path: '/admin/system/trends', icon: '📈', description: '대시보드 지표의 일자별 추이 · 기간 합계', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -120, name: '판매 통계', path: '/admin/system/sales-stats', icon: '📊', description: '상품 판매 랭킹 · 카테고리별 분포', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      // 작업 큐도 환불 운영과 같은 이유로 등급이 낮다 — 밀린 주문을 실제로 처리하는 쪽이 MANAGER 다.
      { id: -121, name: '작업 큐', path: '/admin/system/order-queues', icon: '📮', description: '밀린 주문 · 기한 초과 · 최장 대기', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      // 수강 신청은 '교육 관리'(과정·차시) 옆이 아니라 그룹 맨 뒤다 — 시드 SQL 과 같은 이유로,
      // 중간에 끼우면 뒤 항목의 sort_order 가 겹친다. 두 사본의 순서가 어긋나면 게이트가 잡는다.
      { id: -122, name: '수강 신청', path: '/admin/system/education-enrollments', icon: '📝', description: '과정별 신청자 · 정원 · 대기 · 취소', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -123, name: '강사 관리', path: '/admin/system/education-lecturers', icon: '🎓', description: '강사 명부 · 전공/강의 분야 · 과정 배정', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -124, name: '팝업 관리', path: '/admin/system/site-popups', icon: '🪟', description: '사이트 팝업 노출 구간 · 순서', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -125, name: '댓글 관리', path: '/admin/system/comment-moderation', icon: '💬', description: '전 게시판 댓글 · 신고 판정 · 가림', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      // roles 는 서버 SecurityConfig 의 /admin/privacy-consents 매처(ADMIN·MANAGER)를 그대로 적는다.
      // 읽기 전용 조회라 등급이 낮다. 다만 '시스템 관리' 그룹 자체가 ADMIN 전용이라 지금은 MANAGER
      // 에게 이 줄이 그려지지 않는다 — 작업 큐·환불 운영과 같은 상태다. 그룹이 열리는 날 같이 열린다.
      { id: -126, name: '동의 이력', path: '/admin/system/privacy-consents', icon: '📝', description: '주문 시점 개인정보 동의 · 문안 버전별 조회', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN', 'MANAGER'] },
      // 상품 옵션은 '옵션 카탈로그'(축·값 사전) 옆이 어울리지만 그 자리의 sort_order 가 차 있어
      // 맨 뒤다. 등급은 ADMIN 만 — 재고 차감이 주문 없이 재고를 줄이고 되돌릴 수 없어서다.
      { id: -127, name: '상품 옵션', path: '/admin/system/product-variants', icon: '🧩', description: '상품별 SKU 재고 · 추가금 · 조합 해석', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      { id: -128, name: '이벤트 프로모션', path: '/admin/system/promotions', icon: '🎉', description: '출석체크 · 럭키박스 캠페인 등록 · 여닫기 · 경품', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
      // 상품 심사 — 셀러가 올린 신청서를 운영자가 승인·반려한다(seller-service 가 서빙).
      // 셀러 콘솔 그룹이 아니라 여기 있는 이유: 대상이 "내 조직"이 아니라 전체 신청서라
      // 스코프로 좁힐 수 없다. 셀러 콘솔에 넣으면 그 그룹 roles 가 USER+ADMIN 이 되어야 하는데,
      // 그러면 운영자에게 자기가 403 을 받는 링크(/seller/products)가 함께 그려진다.
      { id: -129, name: '상품 심사', path: '/admin/system/product-submissions', icon: '🔍', description: '셀러 상품 등록 신청 승인 · 반려', area: 'SYSTEM', type: 'ITEM', roles: ['ADMIN'] },
    ],
  },
  {
    id: -8, name: '주문하기', path: '/order', icon: '🛒', description: null,
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    id: -9, name: '추천받기', path: '/recommend', icon: '✨', description: null,
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 대량주문 — 관리자 기능이 아니라 구매자가 자기 주문을 올리는 경로다(초안은 올린 사람만 본다).
    id: -11, name: '대량주문', path: '/order/bulk', icon: '📦', description: 'CSV 업로드 → 검증 → 실주문 전환',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 나눠 결제 — 포인트·상품권 원장을 다 만들고도 쓸 화면이 없던 자리를 채운다.
    id: -12, name: '나눠 결제', path: '/order/pay', icon: '💳', description: '포인트·상품권·카드 혼합 결제',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    id: -10, name: '내 포인트·상품권', path: '/my/balances', icon: '🪙', description: null,
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    id: -13, name: '내 알림', path: '/notifications', icon: '🔔', description: '주문·결제 실시간 알림',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 화면 URL 이 /inquiries 가 아닌 이유: 그건 이 화면이 부르는 API 경로이고, nginx 두 벌이
    // inquiries 세그먼트를 게이트웨이로 프록시한다. 같으면 새로고침에서 목록 JSON 이 렌더된다.
    id: -14, name: '내 문의', path: '/my/inquiries', icon: '💬', description: '상품 문의 · 주문 문의 · 1:1 문의',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 여러 곳 배송 — 화면 URL 이 /orders/multi-destination 이 아닌 이유는 '내 문의'와 같다.
    // orders(복수) 는 nginx 가 게이트웨이로 프록시하는 API 세그먼트다.
    id: -15, name: '여러 곳 배송', path: '/order/multi-destination', icon: '🚚',
    description: '한 번에 담고 여러 주소로 나눠 보내기',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 배송지 주소록 — 화면은 /my/addresses 이고 API 는 /users/{id}/shipping-addresses 다.
    // 위 둘과 같은 이유로 화면 경로를 API 세그먼트와 겹치지 않게 둔다.
    id: -16, name: '배송지 주소록', path: '/my/addresses', icon: '📒',
    description: '자주 쓰는 배송지 저장 · 기본 배송지 지정',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 포인트 선물 — 화면은 /my/point-transfer 이고 API 는 /api/points/transfers 다.
    // 화면 경로에 담을 식별자가 없다(보내는 이를 토큰에서 읽으므로).
    id: -17, name: '포인트 선물', path: '/my/point-transfer', icon: '🎁',
    description: '내 포인트를 다른 회원에게 보내기',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 카테고리 탐색 — 화면은 /browse 이고 API 는 /categories 다. 위 셋과 같은 이유로
    // 화면 경로를 API 세그먼트와 겹치지 않게 둔다. 고른 분류는 ?category=슬러그로 남는다.
    id: -18, name: '카테고리 탐색', path: '/browse', icon: '🧭',
    description: '분류를 골라 그 안의 상품 둘러보기',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 이벤트 — 화면은 여기(SPA), API 는 marketing-service 가 서빙한다(ADR 0045).
    id: -19, name: '이벤트', path: '/promotions', icon: '🎁',
    description: '출석체크 · 럭키박스 참여',
    area: 'SHOP', type: 'ITEM', roles: ['USER'],
  },
  {
    // 파트너 콘솔 — 입점 기업이 자기 매출·주문을 보는 자리다. 이 저장소에서 CORP 영역에
    // 처음 들어오는 줄이다.
    //
    // roles 가 ['USER'] 인 것은 실수가 아니다. 이 저장소의 메뉴 역할 어휘는 ADMIN·MANAGER·
    // USER 뿐이고 PARTNER 라는 역할이 없다. 없는 역할을 여기에 적으면 서버는 그 역할을 모르니
    // 아무도 막지 못하는데 화면에는 통제가 있는 것처럼 보인다 — 빈 통제는 보호가 아니라 착각만
    // 준다. 그래서 메뉴는 로그인까지만 걸고, 입점사가 아닌 계정이 눌렀을 때 서버가 403
    // NOT_A_PARTNER 로 돌려주면 화면이 "이 계정은 입점 조직에 속해 있지 않습니다" 를 그린다.
    // 즉 차단 지점은 하나(서버)이고, 메뉴는 그 사실을 가리지 않는다.
    id: -2, name: '파트너 콘솔', shortName: '파트너', path: '/partner', icon: '🏢',
    description: '입점 기업 매출 · 주문 조회',
    area: 'CORP', type: 'GROUP', roles: ['USER'],
    children: [
      { id: -22, name: '매출 대시보드', path: '/partner', icon: '📈', description: '기간별 매출 · 일자별 추이 · 인기 상품', area: 'CORP', type: 'ITEM', roles: ['USER'] },
      { id: -23, name: '주문 내역', path: '/partner/orders', icon: '🧾', description: '결제 건별 조회 · CSV 내려받기', area: 'CORP', type: 'ITEM', roles: ['USER'] },
    ],
  },
  {
    // 셀러 콘솔 — 파는 쪽이 자기 상품을 올리고 자기 주문을 처리하는 자리다. SELLER 영역에
    // 처음 들어오는 줄이다(enum 에는 있었는데 행이 하나도 없었다).
    //
    // roles 가 ['USER'] 인 것은 파트너 콘솔과 같은 이유다 — 이 저장소의 메뉴 역할 어휘에
    // SELLER 가 없고, 없는 역할을 적으면 아무도 막지 못하는데 원장에는 통제가 있는 것처럼
    // 남는다. 차단 지점은 하나(서버)다: 셀러 조직이 아니면 403 NOT_A_SELLER_MEMBER,
    // 조직은 맞는데 파는 쪽이 아니면 422 NOT_A_SELLER_ORG 이고 화면이 그 문구를 그린다.
    //
    // 심사 화면은 여기 없다 — 위 '시스템 관리'의 자식이다(-129). 이유는 그 줄에 적었다.
    id: -20, name: '셀러 콘솔', shortName: '셀러', path: '/seller/products', icon: '🏪',
    description: '셀러 상품 등록 · 주문 출고',
    area: 'SELLER', type: 'GROUP', roles: ['USER'],
    children: [
      { id: -24, name: '상품 등록', path: '/seller/products', icon: '📦', description: '상품 등록 신청서 작성 · 수정 · 심사 요청', area: 'SELLER', type: 'ITEM', roles: ['USER'] },
      { id: -25, name: '주문 · 출고', path: '/seller/orders', icon: '🚚', description: '내 상품 주문 조회 · 송장 등록', area: 'SELLER', type: 'ITEM', roles: ['USER'] },
    ],
  },
];

const accessible = (node: FallbackMenuNode, role: string | null): boolean =>
  node.roles === null || (role !== null && node.roles.includes(role.toUpperCase()));

const toNavNode = (node: FallbackMenuNode, children: NavMenuNode[]): NavMenuNode => ({
  id: node.id,
  name: node.name,
  label: node.shortName ?? node.name,
  path: node.path,
  icon: node.icon,
  description: node.description,
  area: node.area,
  type: node.type,
  children,
});

/**
 * 폴백 트리를 역할로 걸러 서버 응답과 같은 모양으로 만든다.
 * 서버와 같은 규칙으로 자식이 전부 사라진 묶음(GROUP)은 걷어낸다.
 */
export const resolveFallbackMenus = (role: string | null): NavMenuNode[] =>
  FALLBACK_MENUS.filter((node) => accessible(node, role))
    .map((node) => toNavNode(node, (node.children ?? []).filter((c) => accessible(c, role)).map((c) => toNavNode(c, []))))
    .filter((node) => node.type !== 'GROUP' || node.children.length > 0);
