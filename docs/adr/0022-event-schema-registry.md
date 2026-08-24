# ADR 0022 — 이벤트 Schema Registry (계약 강제 + 호환성 검증)

- 상태: Proposed (설계 — ADR 0020 Phase 5.3 분화. 구현 전 의사결정 고정)
- 일자: 2026-06-17
- 참고: 대안 검토의 "JSON + JSON Schema 검증(경량)"은 [ADR 0024](0024-event-contract-as-code.md) 로
  선행 실행됨 (2026-07-07) — 본 ADR 의 SR 마이그레이션 착수 시 0024 의 스키마가 Avro IDL 원본이 된다.

> ADR 0020(order↔settlement DB 물리 분리) Phase 5.3 의 상세 결정. 본 ADR 은 **설계만** 다루며
> 코드 변경은 포함하지 않는다(인프라 도입 시 별도 PR).

## 컨텍스트

현재 도메인 이벤트는 **스키마 없는 JSON 문자열**로 Outbox 에 적재되어 Kafka 로 발행된다
(`OutboxBacked*EventPublisher` 가 `ObjectMapper.writeValueAsString`, 컨슈머가 `readTree` 로 파싱).

ADR 0020 으로 settlement 가 order DB 를 직접 읽지 않고 **이벤트에 동봉된 데이터**(Phase 1 enrich:
`amount·sellerId·sellerTier·settlementCycle·productName·capturedAt·paymentMethod·pgTransactionId`)
로 정산을 만들면서, **이벤트 페이로드 자체가 서비스 간 계약**이 되었다. 소비자는 다수다:

- settlement: payment/order/user/product 프로젝션 + 정산 생성
- loan-service: `settlement.created/confirmed` 수신 (ADR 0020 / loan)

**문제**: 계약을 강제하는 장치가 없다. 프로듀서가 필드명을 바꾸거나 타입을 바꾸거나 제거해도
배포가 통과하고, 런타임에 컨슈머가 깨진다(파싱 실패 → DLT, 또는 더 나쁘게 조용한 null).
JSON 은 "있으면 읽고 없으면 null" 이라 **삭제·의미변경을 감지하지 못한다**. 금융 도메인에서
이런 무성 드리프트는 금액 오류로 직결될 수 있다(`cashflow_reconciliation_mismatch` 의 잠재 원인).

Phase 5.2 의 cross-DB 대사(건수)는 *결과*의 드리프트를 사후 탐지하지만, *계약*의 드리프트를
**배포 시점에 차단**하는 것이 Phase 5.3 의 목표다.

## 결정

이벤트에 **Schema Registry 기반 계약 강제 + 호환성 게이트**를 도입한다.

### 1. 레지스트리: Redpanda 내장 Schema Registry

신규 인프라를 세우지 않는다. **현재 사용 중인 Redpanda 가 Confluent 호환 Schema Registry 를
내장**한다(`--pandaproxy`/SR 포트). 즉 Confluent `KafkaAvroSerializer` 계열 클라이언트와
`/subjects` REST API 를 그대로 쓸 수 있어 운영비 증가가 사실상 없다.
(독립 운영이 필요해지면 Apicurio 로 분리 가능 — 둘 다 Confluent SR API 호환.)

### 2. 직렬화 포맷: Avro

| 후보 | 채택 | 이유 |
|---|---|---|
| **Avro** | ✓ | 스키마-데이터 분리, 컴팩트, SR 1급 지원, 호환성 규칙 성숙. JVM 친화 |
| Protobuf | △ | 다언어/gRPC 공유 시 유리하나 본 프로젝트는 JVM 단일 + 이미 Avro 생태계 충분 |
| JSON Schema | △ | 가장 점진적(현 JSON 유지)이나 컴팩트성·툴링이 Avro 대비 약함. 경량 대안으로 보존 |

### 3. 호환성 정책: FULL_TRANSITIVE

금융 도메인 안전을 위해 가장 엄격한 **FULL_TRANSITIVE**(backward+forward, 전 이력 대상).
- 허용: optional(default 보유) 필드 **추가**, 필드 **삭제**(default 보유 시).
- 금지: 타입 변경, 의미 변경, default 없는 필드 추가/필수화.
- 효과: 프로듀서·컨슈머 **배포 순서에 무관**하게 안전. enrich(Phase 1)는 default 보유 optional
  추가이므로 정책을 통과한다.

### 4. subject 전략: TopicNameStrategy

subject = `<topic>-value` (예: `lemuel.payment.captured-value`). 토픽=이벤트종류 1:1 이라 단순.
key 는 aggregateId(String)로 스키마 불필요(ADR 0020 Phase 5.4).

### 5. Outbox 연계

Outbox 는 계속 **DB 에 직렬화 산출물을 저장**한다. 변경점은 발행 직전 직렬화 계층:
- `OutboxBacked*EventPublisher` 가 payload 를 Avro(스키마 id 임베드 바이트, base64 또는 bytea)로 적재,
  또는 outbox 는 JSON 유지하고 `KafkaOutboxPublisher` 가 발행 시 Avro 로 변환.
- 후자(발행 시 변환)가 outbox 스키마 변경을 피해 더 점진적 → **권장**. 단 발행기 측에서
  topic→스키마 매핑을 알아야 하므로 enrich 계약을 Avro IDL 로 명문화한다.

### 마이그레이션 단계 (점진, 역행가능)

| 단계 | 내용 | 검증 |
|---|---|---|
| 0 | Redpanda SR 활성화 확인 + CI 에 `register/compatibility` 잡 추가 | SR `/subjects` 응답 |
| 1 | 현행 JSON 페이로드를 Avro IDL(`.avdl`)로 명문화·등록(아직 미사용) | 스키마 등록 성공 |
| 2 | 컨슈머를 **Avro+JSON 둘 다 수용**(content-type/매직바이트 분기)으로 확장 | 기존 JSON 계속 처리 |
| 3 | 프로듀서를 Avro 직렬화로 전환(토픽 단위 롤아웃) | dual-run 대사 일치 |
| 4 | CI 호환성 게이트를 **차단 모드**로(비호환 스키마 PR fail) | 위반 PR 빌드 실패 |
| 5 | JSON 경로 제거(모든 토픽 Avro 전환 확인 후) | DLT 0, 대사 일치 |

각 단계는 독립 배포·롤백 가능(ADR 0020 Strangler 원칙 계승).

## 결과

### 좋아지는 점
- 프로듀서-컨슈머 **계약 드리프트를 배포 시점에 차단**(런타임 DLT/무성 null 예방)
- 스키마가 곧 문서 — enrich 계약이 코드 밖 단일 출처로 명문화
- 컴팩트 직렬화로 토픽 용량·네트워크 절감

### 트레이드오프 / 리스크
- 직렬화 계층 + 빌드(Avro codegen) 복잡도 증가
- 스키마 진화 규율 필요(개발자 학습 비용)
- 마이그레이션 기간 dual-format 처리 코드 한시 존재
- 잘못된 호환성 설정은 되레 배포를 과도 차단 → 단계 4 전 충분한 dry-run

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **현행 JSON 유지** | △(기본값) | 가장 단순하나 계약 강제 0 — Phase 5.3 목표 미달 |
| **JSON + JSON Schema 검증(경량)** | △ 보류 | 외부 레지스트리 없이 컨슈머/CI 에서 JSON Schema 검증. 점진적이나 호환성 자동판정·툴링 약함. SR 도입이 부담일 때의 fallback |
| **Avro + Redpanda 내장 SR (본 결정)** | ✓ | 추가 인프라 0, 호환성 게이트 성숙, JVM 친화 |
| **Protobuf + Confluent SR** | ✗ | 다언어 이점 불필요, 별도 SR 인프라 운영비 |

## 참조

- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
- [0005 — Kafka vs ApplicationEvents](0005-kafka-vs-application-events.md)
- [0017 — Kafka 컨슈머 DLT + Replay](0017-kafka-consumer-dlt-and-replay.md)
- 0020 — order↔settlement DB 물리 분리 (Phase 5.3)
