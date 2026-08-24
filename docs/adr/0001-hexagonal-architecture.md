# ADR 0001 — 헥사고날 아키텍처 (Ports & Adapters)

- 상태: Accepted
- 일자: 2026-01-12

## 컨텍스트

Lemuel 은 주문·결제·정산·시공예약·선정산대출이 얽힌 금융 도메인이다. 비즈니스 규칙(수수료율,
홀드백, 정산 상태 전이, 환불 동시성)이 핵심 자산이며, 외부 의존성(PostgreSQL, Kafka,
Elasticsearch, Toss Payments)은 시간이 지나면 교체·추가된다.

전통적 3-tier(Controller → Service → Repository) 레이어드 구조는 도메인 모델이 JPA 엔티티와
한 몸이 되기 쉽다. 그 결과 (1) 비즈니스 규칙이 `@Entity` 안의 setter 로 흩어지는 anemic domain
model 로 흐르고, (2) 영속·웹·메시징 프레임워크가 도메인 코어로 역류해 단위 테스트가 Spring
컨텍스트·DB 없이는 돌아가지 않으며, (3) 외부 어댑터 교체가 도메인 변경으로 번진다.

금융 규칙을 프레임워크와 무관하게 빠르게 검증하고, settlement↔order 처럼 서비스 간 코드 의존을
0 으로 유지하려면(README / ADR 0020) 의존 방향을 구조적으로 강제하는 아키텍처가 필요하다.

## 결정

각 서비스 내부를 **헥사고날 아키텍처(Ports & Adapters)** 로 구성하고, 계층 의존 방향을
ArchUnit 으로 CI 에서 강제한다.

### 1. 계층 구조

```
{service}/.../{domain}/
├── domain/              # 순수 POJO 도메인 모델 (프레임워크 의존 0)
├── application/
│   ├── port/in/         # 인바운드 포트 (UseCase 인터페이스)
│   ├── port/out/        # 아웃바운드 포트 (영속/외부 연동 인터페이스)
│   └── service/         # UseCase 구현 (포트만 의존)
└── adapter/
    ├── in/web|kafka|batch/   # 인바운드 어댑터 (포트 호출)
    └── out/persistence|external|event|search|pdf|readmodel/  # 아웃바운드 어댑터 (포트 구현)
```

### 2. 의존 방향 규칙

- 도메인은 어떤 바깥 계층도 모른다 — `Settlement`, `OutboxEvent` 등은 Spring/JPA import 0.
- 애플리케이션 서비스는 포트 인터페이스에만 의존하고, 어댑터 구현체(JPA 리포지토리)를 직접
  참조하지 않는다.
- 어댑터가 포트를 구현하고 DI 로 주입된다 — 의존이 항상 안쪽(도메인)을 향한다.

### 3. ArchUnit 으로 CI 강제

`order-service/src/test/java/.../architecture/HexagonalArchitectureTest.java` 가 규칙을 검증한다:

- `domainShouldNotDependOnSpringOrJpa` — `..domain..` 패키지가 `org.springframework..`,
  `jakarta.persistence..`, `javax.persistence..` 에 의존하면 실패.
- `applicationServiceShouldNotUseJpaRepositoryDirectly` — `..application.service..` 가
  `..adapter.out.persistence..` 에 의존하면 실패.
- `adaptersShouldNotDirectlyReferenceOtherDomainsPersistence` — 어댑터가 타 도메인의
  `adapter.out.persistence` 를 직접 import 하면 실패(CQRS 읽기/집계 전용 클래스는 명시적
  허용 목록으로만 예외).
- `portsShouldBeInterfaces` — `..application.port..` 의 `*Port` 는 인터페이스여야 한다.

규칙은 위반 코드가 남은 동안 한시적 허용 목록(예: `EcommerceCategoryService`,
`ProductImageService`, `SettlementQueryRepositoryImpl`)을 두되, 별도 리팩터 태스크로
정리하도록 주석에 명시한다 — 게이트를 끄지 않고 점진 수렴한다.

## 결과

### 좋아지는 점

- 도메인 규칙(수수료·홀드백·상태 전이)이 POJO 단위 테스트로 Spring·DB 없이 즉시 검증된다 —
  JaCoCo 핵심 도메인 패키지 INSTRUCTION 80% 게이트(build.gradle.kts)와 잘 맞는다.
- 외부 의존성(PG, Kafka, ES) 교체가 어댑터 교체로 국소화된다.
- settlement↔order 코드 의존 0 같은 경계가 ArchUnit 위반으로 즉시 드러나, 회귀가 PR 에서 차단된다.

### 트레이드오프 / 리스크

- 포트/어댑터 인터페이스로 보일러플레이트가 늘어 단순 CRUD 도 계층을 거친다.
- 도메인 모델과 JPA 엔티티를 분리하면 매핑 코드가 추가된다.
- 허용 목록이 방치되면 게이트가 무력화될 수 있어, 예외는 리팩터 백로그로 관리해야 한다.

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **레이어드 3-tier (Controller-Service-Repository)** | ✗ | 도메인이 JPA 엔티티와 결합 → anemic model·프레임워크 역류, 경계 강제 수단 없음 |
| **Anemic domain + 트랜잭션 스크립트** | ✗ | 금융 규칙이 서비스로 흩어져 테스트·재사용 곤란 |
| **헥사고날 (Ports & Adapters) + ArchUnit (본 결정)** | ✓ | 도메인 순수성·교체 용이성 확보, 의존 방향을 CI 로 자동 강제 |

## 참조

- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
- [0009 — Spring Boot 4 마이그레이션 + 모듈 분리](0009-boot4-migration-module-split.md)
- 0020 — order↔settlement DB 물리 분리
