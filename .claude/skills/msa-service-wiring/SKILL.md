---
name: msa-service-wiring
description: 신규 마이크로서비스 모듈을 추가하거나 기존 서비스에 새 도메인 패키지를 붙일 때, 또는 코드가 존재하는데 404/500/빈 화면/이미지 빌드 실패가 날 때 로드. 배선 누락은 컴파일이 잡아주지 않고 런타임에 조용히 실패한다.
---

# MSA 서비스·도메인 배선 체크리스트

배선 누락은 **컴파일 에러가 아니라 조용한 404** 로 나타난다 (실사고: fix `36ac0234` —
메뉴·공통코드·RBAC 코드가 존재했으나 5곳 미배선으로 프론트 3개 페이지 크래시).

## A. 기존 서비스에 새 도메인 패키지 추가 — 5곳 배선

| # | 배선 지점 | 파일 | 누락 시 증상 |
|---|---|---|---|
| 1 | 컴포넌트 스캔 | `LemuelApplication.java` `scanBasePackages` | 핸들러 미등록 → 404 |
| 2 | JPA 스캔 | `config/PersistenceConfig.java` `@EntityScan`·`@EnableJpaRepositories` | 리포지토리 빈 없음 → 500 |
| 3 | 게이트웨이 라우트 | `gateway-service/src/main/resources/application.yml` `Path=` predicate | 8080 경유만 404 |
| 4 | nginx | `nginx.conf` / `frontend/nginx.conf`·`nginx.compose.conf` | 프론트(3000) 경유만 404 |
| 5 | Dockerfile | 루트 `Dockerfile` 모듈 `COPY` 목록 | settings 평가 실패 → **전체 이미지 빌드 깨짐** |

진단 순서: 직접 포트(예: 8088) → gateway(8080) → nginx(3000) 순으로 같은 경로를 호출해
**어느 층부터 404 인지**로 누락 지점을 특정한다.

## B. 신규 서비스 모듈 추가

1. `settings.gradle.kts` 모듈 선언 + 루트 `Dockerfile` COPY (5번 함정 동일).
2. **DB-per-service**: 전용 DB + 자체 Flyway (`V{timestamp}__` 명명). 다른 서비스 DB 조인 금지.
3. **shared-common 의존 3단 결정**: 전체 의존 / 제한 스캔 / 미의존(공개 read-only 위성).
   제한 스캔이면 shared-common 빈은 전역 스캔되지 않는다 — 명시적 `@Import` 필수
   (예: company `SecurityConfig` 의 `@Import({JwtUtil.class, JwtAuthenticationFilter.class})`).
   Jackson 도 동일: 레거시 `ObjectMapper` 빈 없음 → `JacksonCompatConfig` `@Import`.
4. `JWT_SECRET` 은 운영 필수(기본값 없음) — 테스트는 부모 `build.gradle.kts` test env 가 주입.
5. 서비스 간 연계는 **Kafka 이벤트만** — 신규 토픽은 `event-contract-change` 스킬 절차.
6. Outbox 재사용 시 스키마 하드코딩 주의 — 폴러/네이티브 쿼리가 `opslab` 스키마를 가정하는
   구간이 있어 신규 DB 도 스키마 정합을 확인해야 한다 (loan 구축 시 실측 함정).
7. JaCoCo LINE 90% 게이트가 subprojects 로 상속된다 — 신규 모듈도 예외 없음.

## C. 하네스 배선 (코드 밖 — 잊기 쉬움)

- `{서비스}-rules` 스킬 생성 + `HARNESS.md` 라우팅 맵에 트리거 행 1개 추가.
- `node scripts/harness/harness-audit.mjs` 로 라우팅 dangling·수치 드리프트 확인.

## 완료 검증

- [ ] 직접 포트·gateway·nginx 3층 모두 대표 경로 200
- [ ] `./gradlew :<module>:test` + `jacocoTestCoverageVerification` 통과
- [ ] `harness-audit.mjs` 통과 (라우팅·인벤토리)
