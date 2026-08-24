# Architecture Decision Records (ADR)

프로젝트에서 내린 주요 설계 결정들. Michael Nygard 템플릿 기반 (Context / Decision / Consequences / Status).

> 번호는 **원본 저장소의 것을 그대로 둔다.** 이 저장소는 커머스·운영 두 축을 떼어 낸 것이라
> 그 축에 해당하지 않는 ADR 은 옮겨 오지 않았고, 그래서 번호가 군데군데 비어 있다.
> 재부여하면 코드·문서에 흩어진 "ADR 00NN" 인용이 전부 거짓이 된다 — 결번이 더 정직하다.

| #                                                               | 제목                                                                                           | 상태     |
| --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | -------- |
| [0001](0001-hexagonal-architecture.md)                          | Hexagonal Architecture (Ports & Adapters)                                                      | Accepted |
| [0003](0003-transactional-outbox-pattern.md)                    | Transactional Outbox 패턴                                                                      | Accepted |
| [0005](0005-kafka-vs-application-events.md)                     | Kafka 도입과 ApplicationEvents 공존                                                            | Accepted |
| [0006](0006-resilience4j-tosspg.md)                             | Toss PG Resilience4j (CB + Retry)                                                              | Accepted |
| [0009](0009-boot4-migration-module-split.md)                    | Spring Boot 4.0 모듈 분리 대응                                                                 | Accepted |
| [0010](0010-multi-pg-routing-and-bulkhead.md)                   | 다중 PG 추상화 + Bulkhead 격벽                                                                 | Accepted |
| [0011](0011-sku-variant-with-optimistic-lock.md)                | ProductVariant (SKU) + Optimistic Lock                                                         | Accepted |
| [0012](0012-distributed-tracing-across-outbox.md)               | Outbox 경계에서 끊기지 않는 분산 트레이싱                                                      | Accepted |
| [0013](0013-split-payment-with-tenders.md)                      | 분할결제 + 역순 환불 정책                                                                      | Accepted |
| [0017](0017-kafka-consumer-dlt-and-replay.md)                   | Kafka 컨슈머 DLT + 운영자 Replay                                                               | Accepted |
| [0021](0021-shared-common-as-platform-library.md)               | shared-common 버전드 플랫폼 라이브러리화                                                       | Accepted |
| [0022](0022-event-schema-registry.md)                           | 이벤트 Schema Registry (Avro + Redpanda SR)                                                    | Proposed |
| [0024](0024-event-contract-as-code.md)                          | 이벤트 계약-as-code (JSON Schema 양방향 계약 테스트)                                           | Accepted |
| [0027](0027-db-partitioning-retention-pk-standard.md)           | DB 파티셔닝·리텐션·PK 전략 표준 + 유지보수 자동화                                              | Accepted |
| [0028](0028-procedural-discipline-plugin-independence.md)       | 절차 규율층 플러그인 독립 내재화 + 이중 라우팅 경계                                            | Accepted |
| [0031](0031-seller-tier-lifecycle.md)                           | 셀러 등급 라이프사이클 (자동 산정 + 변경 이력 + 강등 유예)                                     | Accepted |
| [0035](0035-kafka-topic-catalog.md)                             | Kafka 토픽 카탈로그 (파티션 수를 코드 안으로 — 키 재해시 차단)                                 | Accepted |
| [0041](0041-notification-absorbed-into-operation.md)             | 폴리글랏 notification-service → operation-service 슬라이스 흡수                                 | Accepted |
| [0042](0042-organization-absorbed-into-order.md)                 | organization-service → order-service 슬라이스 흡수 (operation 안 기각 — 관리 화면은 병합 기준이 아니다) | Accepted |
| [0043](0043-board-education-absorbed-into-operation.md)          | board·education → operation-service 슬라이스 흡수 (operation 을 "플랫폼 운영 서비스"로 재정의)       | Accepted |

> **0019 결번**: 0019 번은 ADR 이 작성된 적이 없다(결번). 문서·코드 어디에도 참조가 없어 유실이 아니라
> 건너뛴 번호로 간주한다. 규칙 1(번호 재사용 금지)에 따라 재할당하지 않는다.

## 규칙

1. 새 ADR 은 번호 증가 순으로. 번호 재사용 금지.
2. 결정이 번복되면 **Superseded by 00XX** 로 상태 변경하고 새 ADR 작성. 구 ADR 삭제 금지 (역사 보존).
3. 주요 결정은 **구현 전** draft 올리고 리뷰 → Accepted. 과거 결정은 retrofit 가능.
