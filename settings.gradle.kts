plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "shop"

// ※ 이 블록의 주석에는 닫는 괄호도 큰따옴표도 쓰지 말 것 — 모듈 로스터 파서가 include 의 첫
//   닫는 괄호까지를 블록으로 보고 그 안의 따옴표 문자열을 모듈명으로 읽는다.
include(
    // 쇼핑몰 커머스 코어 — 회원·상품·장바구니·주문·결제·포인트·기프트카드·쿠폰·리뷰·배송·조직
    "order-service",
    // 운영 — 게시판·알림 팬아웃/SSE·관제 인시던트/신호/이상탐지·교육
    "operation-service",
    // 마케팅 — 이벤트 프로모션 운영 도구. 출석체크·럭키박스. 보상은 포인트 원장에 직접 쓰지 않고
    // lemuel.marketing.reward_requested 로 요청만 낸다 - 원장 오너십은 order 가 유지한다
    "marketing-service",
    // 파트너 콘솔 — 우리 몰에 입점한 기업이 자기 매출/주문만 보는 읽기 전용 백오피스.
    // 사실은 전부 다른 서비스의 이벤트로 받는 사본이고 여기서 쓰는 API 는 없다
    "partner-service",
    // 셀러 백오피스 — 입점 셀러가 자기 상품을 직접 등록/수정하고 자기 주문의 출고를 처리한다.
    // partner-service 와 달리 원본을 갖는다 — 상품 등록 신청서가 그것이다. 다만 카탈로그 상품과
    // 배송은 order-service 소유라 여기서 직접 쓰지 않고 이벤트로 요청만 낸다
    "seller-service",
    // API Gateway — 라우팅만, 자체 인증 필터 없음
    "gateway-service",
)

// shared-common 은 독립 빌드로 분리한 버전드 내부 라이브러리다.
// composite build 로 합성 → 서비스가 선언한 github.lms.lemuel:shared-common:<ver> 의존을
// 로컬에서는 이 included build 로 자동 치환하고, 배포 시에는 publish 된 아티팩트로 소비한다.
includeBuild("shared-common")
