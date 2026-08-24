# Fashion Copilot — 상시 코어 규칙

이 저장소(또는 이 플러그인이 설치된 커머스 코드베이스)에서 작업할 때 **항상** 지켜야 하는 최소 규칙.
상세 규칙은 상황별 skill(`skills`)이 로드한다.

## 돈 (Money)

- 가격·할인·환불액 연산은 **BigDecimal** 만 사용한다. `float`/`double` 로 금액을 다루는 코드가
  보이면 작성하지 말고, 기존 코드에서 발견하면 반드시 지적하라.
- 쿠폰 할인의 절사 정책은 **FLOOR** 가 코드베이스 표준(`CouponType.PERCENTAGE`) — 임의로
  HALF_UP 등으로 바꾸지 마라. 할인 상한(`maxDiscountAmount`) 클램프는 절사 **이후**에 적용한다.

## 재고 (드랍 오버셀 방지)

- 재고 변경은 **원자적 조건부 UPDATE**(`... SET stock = stock - :qty WHERE stock >= :qty`)
  경로로만 한다 (`decreaseStockIfAvailable`). 읽고-빼고-쓰는(read-modify-write) 재고 코드를
  만들지 마라 — 드랍 순간 오버셀로 직결된다.
- 재고의 진실 원천은 **variant**(`product_variants.stock_quantity`)다.
  `products.options_json` 은 옵션 트리 원본일 뿐 재고 판단에 쓰지 마라.

## 환불 (반품 최다 빈도 금전 경로)

- 환불 멱등키 규칙: **전액 환불 = 자동키**(`payment-{id}-full`), **부분 환불 = 호출자 필수키**.
  부분 환불 API 를 만들면서 Idempotency-Key 를 옵션으로 두지 마라.
- 환불 가능액(`refundableAmount`) 초과 환불은 어떤 경로로도 허용하지 마라.
- 주문 상태는 **전액 도달 시에만** REFUNDED 로 전이한다. 부분 환불은 주문 상태를 바꾸지 않는다.

## 쿠폰

- 쿠폰 사용량 증가는 원자적 `incrementUsageIfAvailable` 로만 한다.
  `usedCount` 를 직접 읽어서 증감하는 코드는 초과 사용(중복 할인) 버그다.
- 1인 1매는 `UNIQUE(coupon_id, user_id)` 하드 제약이 최종 방어선 — 소프트 체크만 믿지 마라.

## 운영 데이터 접근

- 운영/스테이징 DB(opslab)에 psql 등으로 **직접 접속하는 쓰기 명령을 생성하지 마라**.
  환불·재고·쿠폰 상태 조회는 `fashion-copilot` MCP 도구
  (`refund_recon`, `refund_health`, `stock_pulse`, `coupon_simulate`, `refund_simulate`)로만 한다.

## 민감정보

- 배송지·연락처·수취인 이름을 로그에 그대로 남기는 코드를 작성하지 마라.
  마스킹 유틸(`shared-common` `common.audit`)을 사용하라.

## 아키텍처

- 헥사고날 규칙: `domain` 은 프레임워크·adapter 를 import 하지 않는다. ArchUnit 테스트가 강제한다.
- 서비스 간 연계는 Kafka 이벤트(Outbox 경유)로만 — 직접 `kafkaTemplate.send()` 금지.
