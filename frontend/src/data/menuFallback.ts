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
    id: -122, name: '반품·교환', path: '/admin/approvals/returns', icon: '🔁',
    description: '승인 · 회수 확인 · 환불 · 교환 재배송',
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
