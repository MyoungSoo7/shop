# ADR 0021 — shared-common 을 버전드 플랫폼 라이브러리로

- 상태: Accepted (구현 완료)
- 일자: 2026-06-05

## 컨텍스트

`shared-common` 은 감사·관측·예외·Outbox·rate limit·JWT·PDF 등 전 서비스가 의존하는 공용 코드를
담는다. 초기에는 루트 멀티모듈의 일반 서브프로젝트(`include("shared-common")` +
`implementation(project(":shared-common"))`)였다.

이 구조는 **빌드 락스텝(lock-step)** 을 강제했다:

- shared-common 이 루트 `subprojects {}` 의 toolchain·dependency-management·jacoco 설정을 그대로
  상속해, 공용 라이브러리인데도 앱 모듈과 한 빌드 그래프에 묶였다.
- 한 서비스만 빌드해도 `:shared-common:compileJava` 가 항상 끌려 들어와, 공용 코드의 사소한 변경이
  전 서비스 빌드/테스트를 재트리거했다.
- shared-common 자체를 버전 단위로 배포·고정(pin)할 좌표가 없어, "어느 서비스가 어느 버전의 공용
  코드를 쓰는가"를 명시적으로 통제하지 못했다.

## 결정

shared-common 을 **독립 빌드 + composite build 로 합성되는 버전드 플랫폼 라이브러리**로 분리한다.
**(구현 완료)**

### 1. 독립 빌드 (java-library + maven-publish)

`../../shared-common/build.gradle.kts` 가 루트 `subprojects {}` 를 더 이상 상속하지 않고
toolchain(Java 25)·repositories·dependency-management(Boot 4.0.4 BOM)·jacoco 를 **자체 선언**한다.
좌표는 `group = "github.lms.lemuel"`, `version = "1.0.0"` — 즉
`github.lms.lemuel:shared-common:1.0.0`.

`maven-publish` 로 발행한다:
- 로컬: `./gradlew -p shared-common publishToMavenLocal`(별도 repo 불필요).
- 원격(GitHub Packages): `GITHUB_ACTOR`/`GITHUB_TOKEN` 이 있을 때만 repository 가 활성화.

`common.outbox.domain` INSTRUCTION 80% 커버리지 게이트도 루트에서 이 빌드로 이관해 라이브러리가
자체 품질을 책임진다.

### 2. composite build 로 합성

루트 `../../settings.gradle.kts` 는 shared-common 을 `include` 하지 않고
**`includeBuild("shared-common")`** 로 둔다. 효과:

- 서비스는 좌표 `github.lms.lemuel:shared-common:1.0.0` 를 의존으로 **선언**한다.
- 로컬 개발: included build 가 그 좌표를 자동 치환(substitute)해, 소스 변경이 즉시 반영된다.
- 배포: publish 된 아티팩트로 소비 → 빌드 그래프 분리.

이로써 공용 코드는 자체 빌드 그래프를 갖고, 서비스는 "어떤 버전을 쓰는가"를 좌표로 명시한다.

## 결과

### 좋아지는 점

- **빌드 락스텝 해소** — shared-common 이 앱 모듈 빌드에 항상 끌려오지 않음. 라이브러리는 독립
  컴파일·테스트·발행된다.
- 버전 좌표(1.0.0)로 의존이 명시적 — 폴리레포 전환 시에도 동일 좌표로 소비 가능(includeBuild 만 제거).
- 로컬은 source substitution 으로 즉시성 유지, CI/배포는 발행 아티팩트로 그래프 분리 — 두 모드를 한
  구성으로 충족.

### 트레이드오프 / 리스크

- composite build / 버전 좌표 substitution 개념의 학습 비용.
- 버전 범프·발행 규율 필요 — 1.0.0 좌표를 깨는 변경 시 소비 서비스 영향 관리.
- 로컬(substitution)과 배포(발행 아티팩트) 경로가 달라, 두 경로의 동등성 검증이 필요.

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **일반 서브프로젝트 `project(":shared-common")` 유지** | ✗ | 빌드 락스텝·전 서비스 재트리거, 버전 좌표 없음 |
| **별도 폴리레포 + 사내 Maven 저장소** | △ | 그래프는 가장 깔끔하나 즉시 반영·로컬 개발 경험 저하, 저장소 운영 부담 |
| **독립 빌드 + composite build(includeBuild) + maven-publish (본 결정)** | ✓ | 락스텝 해소 + 로컬 즉시성 + 버전 좌표 명시, 폴리레포 전환 여지 보존 |

## 참조

- [0001 — 헥사고날 아키텍처 (Ports & Adapters)](0001-hexagonal-architecture.md)
- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
- [0009 — Spring Boot 4 마이그레이션 + 모듈 분리](0009-boot4-migration-module-split.md)
