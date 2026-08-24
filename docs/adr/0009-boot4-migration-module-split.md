# ADR 0009 — Spring Boot 4 마이그레이션 + 모듈 분리

- 상태: Accepted
- 일자: 2026-02-10

> **Update (이후 변경)**: 본문의 모듈 표는 분리 시점(5 서비스)을 기록한다. 이후 `reservation-service`
> (시공 예약/기사 배정)는 **제거**됐고(모듈·자체 DB·gateway 라우팅·매니페스트 정리), 반대로 loan(기업대출)·
> financial·economics·company·operation·market·ai·common-data·investment·account·organization·card·
> insurance·deposit 서비스가 추가되어 현재는 **16 서비스 + gateway**(`../../settings.gradle.kts` 17 모듈)다.
>
> 또한 아래 표의 gateway 책임에 적힌 "인증"은 **실현되지 않았다** — 게이트웨이는 경로 라우팅 전용이고,
> JWT 인증·rate limit 은 각 서비스의 `shared-common` 필터 체인이 담당한다(중앙 인증 게이트 대신
> 서비스별 심층 방어). 대응 관계는
> [ARCHITECTURE §7](../../ARCHITECTURE.md#7-금융권-아키텍처-용어-대응-mci--eai--esb--fep) 참조.
>
> Java 25 / Boot 4 / 멀티모듈 분리라는 본 결정은 그대로 유효하다.

## 컨텍스트

Lemuel 은 본래 단일 모놀리스였다. 주문·결제·정산·시공예약·선정산대출이 한 배포 단위에 묶여 있어
(1) 한 도메인 변경이 전체 재배포를 유발하고, (2) 정산 배치/대사 부하가 주문 트래픽과 자원을
다투며, (3) Bounded Context 경계가 코드 상으로 강제되지 않아 도메인 간 결합이 누적됐다.

동시에 런타임을 현대화할 필요가 있었다 — Java 25(가상 스레드·최신 GC)와 Spring Boot 4 세대
(Spring Framework 7, Spring Cloud 2025)로 올려 장기 지원과 신규 기능을 확보한다. 빌드도
멀티 컨텍스트를 다룰 수 있는 구조여야 했다.

## 결정

런타임을 Java 25 / Spring Boot 4 로 올리고, 모놀리스를 Gradle 멀티모듈로 분리한다.

### 1. 런타임 / 빌드 스택

루트 `../../build.gradle.kts` 가 전 모듈 공통 설정을 `subprojects {}` 로 적용한다:

- **Java 25** — `toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }`.
- **Spring Boot 4.0.4** — `org.springframework.boot` 4.0.4 (루트에서 `apply false`,
  각 앱 모듈이 적용).
- **Spring Cloud 2025.1.0** — `mavenBom` 으로 BOM import(Gateway 등).
- **Gradle Kotlin DSL** — 모든 빌드 스크립트 `.kts`.
- 공통 JaCoCo 커버리지 게이트(LINE 50%, 핵심 도메인 패키지 INSTRUCTION 80%)와 SonarQube 를
  루트에서 일괄 구성.

### 2. 모듈 분리 — 5 서비스 + 공용 라이브러리

`../../settings.gradle.kts` 가 다음을 선언한다:

```
include("order-service", "settlement-service", "reservation-service",
        "loan-service", "gateway-service")
includeBuild("shared-common")
```

| 모듈 | 책임 |
|---|---|
| order-service | user·order·payment·cart·shipping·product·category·coupon·review·game (거래 컨텍스트) |
| settlement-service | settlement·payout·ledger·chargeback·pgreconciliation·report (정산·원장·대사·리포트) |
| reservation-service | 시공 예약/기사 배정 (자체 DB) |
| loan-service | 선정산 대출 (자체 DB·자체 원장) |
| gateway-service | Spring Cloud Gateway 라우팅·인증 |
| shared-common | 감사·관측·예외·Outbox·rate limit·JWT·PDF (전 서비스 공유) |

각 서비스는 ADR 0001 의 헥사고날 구조를 내부에 갖고 독립 배포된다. 서비스 간 통신은 Kafka 이벤트
(ADR 0005)와 이벤트 기반 프로젝션으로만 이뤄지며, settlement↔order 코드 의존은 0 이다
(ADR 0020 으로 DB 도 물리 분리 — settlement-service 는 자체 `settlement_db` 와
`settlement_*_view` 프로젝션 사용).

`shared-common` 은 단순 서브모듈이 아니라 `includeBuild` composite build 로 합성되는 버전드
라이브러리다(상세는 ADR 0021).

## 결과

### 좋아지는 점

- 도메인별 독립 배포 — 한 서비스 변경이 전체 재배포를 유발하지 않음.
- 정산 배치/대사 부하와 주문 트래픽이 자원·스케일링에서 분리됨.
- Bounded Context 경계가 모듈 경계로 물리화되어 ArchUnit·모듈 의존으로 강제됨.
- Java 25 / Boot 4 / Cloud 2025 로 런타임·프레임워크 장기 지원 확보.

### 트레이드오프 / 리스크

- 분산 시스템 복잡도 증가 — 이벤트 정합성, 멱등, 관측(분산 trace)이 필수가 됨.
- 멀티모듈 빌드 구성·CI 파이프라인 복잡도 상승.
- Boot 4 / Java 25 세대 전환에 따른 라이브러리 호환성 점검 비용(예: Jackson 빈 스캔 변경 등).

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **모놀리스 유지 + Boot 4 만 업그레이드** | ✗ | 배포 결합·자원 경합·컨텍스트 경계 미강제 문제 미해결 |
| **단일 멀티모듈(한 배포) 로만 분리** | △ | 코드 경계는 생기나 독립 배포·스케일 분리 불가 |
| **Java 25 / Boot 4 + 5 서비스 멀티모듈 분리 (본 결정)** | ✓ | 독립 배포·자원 분리 + 컨텍스트 경계 물리화 + 런타임 현대화 |

## 참조

- [0001 — 헥사고날 아키텍처 (Ports & Adapters)](0001-hexagonal-architecture.md)
- [0005 — Kafka vs ApplicationEvents](0005-kafka-vs-application-events.md)
- 0020 — order↔settlement DB 물리 분리
- [0021 — shared-common 을 버전드 플랫폼 라이브러리로](0021-shared-common-as-platform-library.md)
