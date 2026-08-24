---
name: event-contract-change
description: cross-service Kafka 토픽을 새로 만들거나 기존 페이로드에 필드를 추가·변경할 때 로드 — 계약 스키마·정본 샘플·양방향 계약 테스트 배선 절차 (ADR 0024). 컨슈머 역직렬화 실패·계약 테스트 FAIL 조사 시에도.
---

# 이벤트 계약 추가·변경 절차 (ADR 0024)

**단일 출처**: `shared-common/src/testFixtures/resources/contracts/events/`
— `lemuel.{도메인}.{이벤트}.schema.json` (JSON Schema) + `samples/` (정본 샘플).
프로듀서·컨슈머가 같은 파일을 검증하므로 드리프트가 빌드 시점에 깨진다.

> 코드 레벨 발행/구독/멱등 규칙은 `idempotency-and-events` 가 정본 — 이 스킬은
> **계약 파일과 테스트를 어디에 어떻게 배선하는가**만 다룬다.

## 신규 토픽 추가 순서

1. **스키마 + 정본 샘플** 을 위 contracts 디렉토리에 작성 (샘플은 스키마를 실제로 통과해야 함).
2. **프로듀서 계약 테스트** — 발행 서비스의 `adapter/out/event/` 에
   `*EventContractTest.java` (기존 예: `OrderEventContractTest`, `SettlementEventContractTest`).
   실발행 페이로드가 스키마·샘플과 정합함을 검증.
3. **컨슈머 계약 테스트** — 소비 서비스의 `adapter/in/kafka/` 에
   `EventContractConsumerTest.java` (기존 예: settlement·loan). 정본 샘플을 역직렬화해
   컨슈머 DTO 가 깨지지 않음을 검증.
4. **소비측 의존** — 소비 서비스 `build.gradle.kts`:
   `testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))`.
5. **문서** — `SPEC.md` §5 토픽 표(프로듀서/컨슈머 매핑)에 행 추가.
6. **컨슈머 멱등 골격** — `idempotency-and-events` 스킬의 `markIfNew` 패턴 적용.

## 기존 페이로드 변경 규칙 — 하위호환만 (ADR 0022)

| 변경 | 허용 | 방법 |
|---|---|---|
| 필드 추가 | ⭕ | optional 로 추가, 스키마·샘플·양측 테스트 동시 갱신 |
| 필드 삭제 / 타입 변경 | ❌ | 신규 토픽 버전으로 분리 (구독자가 전부 이관할 때까지 병행) |

## 함정

- **스키마만 고치고 샘플 미갱신** — 계약 테스트가 잡아주지만, "테스트가 왜 깨졌지"로
  헤매지 말 것: 스키마·샘플·프로듀서·컨슈머 4자가 항상 같이 움직인다.
- **프로듀서만 수정하고 소비측 미확인** — 컨슈머가 여러 서비스일 수 있다.
  `grep -r "토픽명"` 으로 전 소비자를 찾아 컨슈머 계약 테스트까지 실행.
- account 는 **소비 전용** — account 에 발행 코드를 넣는 계약 변경은 하드스톱 위반.

## 완료 검증 (작성·검증 분리)

- [ ] 프로듀서·컨슈머 **양쪽 모듈** `./gradlew :<module>:test` 통과
- [ ] `event-contract-reviewer` 에이전트로 schema↔producer↔consumer 3자 정합 검토
