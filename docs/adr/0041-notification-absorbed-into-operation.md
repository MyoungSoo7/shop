# ADR 0041 — 폴리글랏 notification-service 를 operation-service 로 흡수

- 상태: Accepted (실행 완료)
- 일자: 2026-08-25
- 관련: ADR 0037(분해 기준 6축) ·
  ADR 0039(금융 그룹 통합) ·
  [ADR 0017](0017-kafka-consumer-dlt-and-replay.md)(DLT·replay) ·
  [ADR 0035](0035-kafka-topic-catalog.md)(토픽 카탈로그) · `docs/sse.md`

## 컨텍스트

ADR 0037 은 18개 서비스를 6축 프레임워크에 대조해 A/B/C 그룹으로 분류했다. 그런데 **폴리글랏 7종은
그 대조에 한 번도 올라간 적이 없다** — 문서 전체에서 market-stream 이 market 서비스 근거 칸에 곁다리로
한 번 언급될 뿐, notification 은 0회다. 즉 이 7종은 "왜 별도 프로세스인가"가 검증된 적 없는 유일한 부류였다.

`notification-service`(Kotlin/Boot 3.3/JDK 21, 8130)를 이제 와서 6축에 올려 보면 이렇다.

| 축 | 판정 |
|---|---|
| ① 정합성 경계 | **약함** — 자체 저장소가 없다(무영속). 어떤 트랜잭션과도 묶이지 않는다 |
| ② 규제/라이선스 | **없음** — 별도 인허가·감사 대상이 아니다 |
| ③ 장애 격리 | **약하게 걸림** — 알림 발송 실패가 결제·정산 경로를 막으면 안 된다 |
| ④ 변경/배포 주기 | **없음** — 알림 템플릿 변경 빈도가 관제와 다르지 않다 |
| ⑤ 팀 인지 부하 | **없음** — main 26파일·1,691 LOC. 한 팀의 일부에도 못 미친다 |
| ⑥ 데이터 오너십 | **없음** — 쓰기 권한을 가진 데이터가 없다. 남의 이벤트를 받아 전달만 한다 |

자체 기준인 **"최소 2축 이상 강하게 걸려야 별도 서비스"** 에 미달한다. 강하게 걸리는 축은 ③ 하나뿐이고,
그 ③은 별도 **프로세스**를 요구하지 않는다 — 요구하는 것은 "알림 실패가 비즈니스 경로로 전파되지 않는 것"이며,
이는 같은 프로세스 안에서도 fire-and-forget 규율로 달성된다(operation-service 의 opssignal 이 이미 그렇게 한다).

## 결정

`notification-service` 를 **operation-service 의 `notification` 슬라이스로 흡수**한다. Kotlin → Java 25 로 포팅하고
폴리글랏 디렉토리는 제거한다.

### 왜 operation-service 인가 (order-service 가 아니라)

흡수 대상 후보로 order-service 가 먼저 거론됐다. 기각한 근거는 셋이다.

1. **order-service 는 컨슈머가 0개다.** `@KafkaListener` 가 하나도 없는 순수 프로듀서이자
   ADR 0037 이 정의한 "나머지 전 서비스의 이벤트 소스"다. 알림은 `settlement.confirmed`·
   `investment.executed`·`payment.refunded` 를 구독해야 하므로, order 에 넣으면 **단방향 이벤트 흐름이
   양방향이 된다**. 코드 의존 없이 이벤트 층에서 역류를 만드는 셈이다.
2. **order 는 이미 인지 부하 한계를 넘었다.** 실측 798파일(settlement 681)로, ADR 0037 이
   "한 팀의 최대치(축⑤)"라 부른 settlement 를 이미 초과한 채 18개 도메인 패키지를 안고 있다.
3. **알림은 커머스 소유가 아니다.** 정산 확정·투자 체결 알림이 주문 서비스에서 나갈 이유가 없다.

operation-service 를 고른 근거는 반대로 셋이다.

1. **이미 같은 모양의 일을 한다.** `DomainEventSignalConsumer` 가 `order.created`·`payment.captured`·
   `settlement.created` 를 cross-domain 으로 구독 중이다. 컨슈머 배선·`app.kafka.enabled` 토글·
   DLT 설정이 그대로 재사용된다.
2. **규율이 동형이다.** operation 의 opssignal 원칙("절대 throw 금지·fire-and-forget")은 알림의 ③축
   요구와 같은 성질이다 — 관측/통지가 비즈니스 경로를 깨면 안 된다.
3. **SSE 허브의 전제와 맞는다.** 푸시 허브는 인메모리 stateful 이라 레플리카가 늘면 재생 창이 갈라진다.
   operation 은 단일 인스턴스 관제 서비스라 이 전제가 자연스럽다.

### 이관하며 의도적으로 바꾼 것

| 항목 | 이관 전 (Kotlin standalone) | 이관 후 (operation 슬라이스) | 이유 |
|---|---|---|---|
| 동시 팬아웃 | 코루틴 `async`/`awaitAll` + `withTimeout` | 가상 스레드 executor + `Future.get(timeout)` | Java 25. 채널당 1 + 시도당 1 가상 스레드 — 시도 스레드를 따로 둬야 블로킹 채널(SMTP)에 상한이 걸린다 |
| Kafka 에러 처리 | 자체 `KafkaErrorHandlingConfig`(Kotlin 사본) | shared-common `KafkaConsumerErrorHandlingConfig` | 사본을 지웠다. 재시도·notRetryable 분류 계약은 동일 |
| **ack 모드** | `RECORD`(리스너에 `Acknowledgment` 없음) | `MANUAL_IMMEDIATE` + 명시 ack | operation yml 이 MANUAL_IMMEDIATE 다. **ack 를 안 넣었으면 영구 미커밋으로 무한 재배달**이 됐다 |
| JWT 시크릿 키 | `app.security.jwt.secret`(자체 리졸버 전용) | `app.jwt.secret`(shared-common 공통) | 별도 키라 운영자가 릴랙스 바인딩 이름을 스스로 알아내야 했고, 그래서 스트림이 늘 503 이던 전력이 있다 |
| 스트림 경로 | `/notifications/stream` (gateway 가 재작성) | `/api/notifications/stream` (재작성 없음) | 게이트웨이·프론트·nginx 의 외부 계약은 **불변**. 재작성 필터만 사라졌다 |
| 발송·데모 경로 | `/notifications/send`·`/demo` | `/internal/notifications/send`·`/demo` | 외부 미노출 성질 보존. `/internal/**` 은 공유 시크릿 필터가 게이팅한다 |
| DLT 메트릭 | `notification_kafka_dlt_published_total` | `operation_kafka_dlt_published_total` | 접두가 `spring.application.name` 유래로 통일. 알람 expr 을 함께 고쳤다 |
| `lemuel.payment.confirmed` 구독 | 구독함 | **구독 안 함** | 유일한 발행자 payment-webhook-service 가 같은 날 제거됐다(프로듀서 0). 분류표는 남긴다 — DLT replay 가 이 토픽명으로 디코딩돼야 한다 |

### 컨슈머 그룹은 이름을 유지한다 (`notification-service`)

저장소 관례는 `lemuel-<모듈>` 이지만 이 리스너만 예외로 둔다. 두 가지가 겹친다.

1. **겹치는 토픽**: 같은 모듈의 `DomainEventSignalConsumer` 도 `lemuel.payment.captured` 를 구독한다.
   같은 그룹이면 카프카가 둘을 한 그룹으로 보고 파티션을 **나눠** 준다 — 신호 컨슈머가 가져간 파티션의
   결제 이벤트는 알림으로 오지 않고, 오프셋까지 공유되어 조용히 유실된다. 팬아웃이므로 그룹이 달라야 한다.
2. **오프셋 승계**: 이 이름을 유지하면 컨테이너 교체가 곧 무결점 인계다. 이름을 바꾸면 새 그룹이 되고
   커밋된 오프셋(실측: `payment.captured` 6파티션 합계 2,810건)을 잃어, 보존기간 안의 결제 이벤트를
   전량 재처리해 **실제 수신자에게 지난 알림을 대량 재발송**하거나(earliest) 전환 중 이벤트를 건너뛴다(latest).

guard 의 `KAFKA-GROUP-OWNER` 는 모듈 yml 의 기본 group-id 를 보므로 리스너 애노테이션 수준의 이 그룹과 충돌하지 않는다.

## 결과

- **폴리글랏 프로세스 2 → 1** (market-stream 만 남는다). 서비스 인벤토리 총계 12 는 그대로다 —
  폴리글랏 묶음은 1로 세고, notification 은 그 묶음 안에서 operation 안으로 이동했을 뿐이다.
- **Kotlin 이 스택에서 사라졌다.** 폴리글랏은 Go 1종(market-stream)만 남는다. `polyglot-ci.yml` 의
  Kotlin 잡과 매트릭스도 함께 제거했다(빈 매트릭스로 영원히 스킵되는 잡을 남기지 않는다).
- **외부 계약 무변경**: 프론트(`frontend/src/api/notificationStream.ts`)·gateway 라우트 경로·nginx
  location 모두 `/api/notifications/stream` 그대로다. 프론트는 한 줄도 고치지 않았다.
- 커버리지 측정 범위가 넓어졌다 — 자바 모듈 게이트는 `adapter/in/web`·`adapter/in/kafka` 를 제외하므로,
  이관 전 전량 측정되던 웹·카프카 어댑터가 게이트 분모에서 빠진다. 테스트는 그대로 옮겼으므로
  검증 자체는 유지되지만 **게이트가 보는 범위는 좁아졌다**(의도된 트레이드오프, 형제 자바 모듈과 동일 기준).

## 대안 (기각)

- **현상 유지**: ADR 0037 기준 미달을 알고도 두는 것은 "기술을 써 보려고 도메인을 꿰맞췄다"는 인상을
  그대로 남긴다. 그 인상은 이 저장소가 명시적으로 피하려는 것이다(ADR 0037 컨텍스트).
- **order-service 흡수**: 위 3가지 근거로 기각.
- **완전 삭제**: 같은 날 제거한 5종(payment-webhook·reconciliation·screening-backtest·settlement-anomaly·
  forecast)과 달리 notification 은 **실제로 쓰이고 있었다** — 컨슈머 그룹 실측에서 `payment.captured`
  2,810건 처리·lag 0 이었고, gateway·compose·프론트 배선이 모두 살아 있었다. 기능을 지울 이유가 없다.
