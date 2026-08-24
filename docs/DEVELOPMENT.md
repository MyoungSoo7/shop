# 개발 참조 — Shop

> CLAUDE.md 에서 분리한 **참조성 정보**(기술 스택·빌드 커맨드·인프라·CI 배경).
> 에이전트가 매 대화에 상주시킬 필요는 없고 필요할 때 조회한다. 강제 규칙·가드레일·DoD 는 [`../CLAUDE.md`](../CLAUDE.md) 참조.

## 기술 스택

| 구분 | 기술 | 구분 | 기술 |
|------|------|------|------|
| 언어 | Java 25 | 메시지 | Kafka (Redpanda 호환) |
| 프레임워크 | Spring Boot 4.0.7 | PG | Toss Payments |
| 빌드 | Gradle 멀티모듈 (Kotlin DSL) | 배치 | Spring Batch |
| Gateway | Spring Cloud Gateway 2025 | 캐시 | Caffeine(L1) + 선택 Redis(L2) |
| DB | PostgreSQL 17 | PDF | iText 8 |
| 검색 | Elasticsearch 8.17 | 마이그레이션 | Flyway (V1~V50 + `V{timestamp}__` 혼재) |
| 관측 | Micrometer + Prometheus | 회복탄력성/RateLimit | Resilience4j / Bucket4j |
| 프론트 | React 19 · TypeScript · Vite | 프론트 테스트 | Vitest · Testing Library · Playwright |

> Boot 4 / Java 25 조합의 알려진 함정(레거시 ObjectMapper 빈 부재, RestClient.Builder 자체 빈 필요,
> 네이티브 @Query 구조적 SpEL 미평가, ArchUnit 1.4.x+ 필요 등)은 각 서비스 코드·ADR 참조.

## 빌드 및 실행 커맨드

```bash
./gradlew build                                     # 전체 빌드
./gradlew :<module>:compileJava                     # 모듈별 컴파일 (예: :order-service:compileJava)
./gradlew :<module>:test                            # 모듈별 테스트
./gradlew :<module>:jacocoTestCoverageVerification  # 커버리지 게이트(측정 정답, LINE 90%)
./gradlew :<module>:bootRun                         # 모듈별 부트런
./gradlew :<module>:bootJar                         # 모듈별 jar

# 모듈(3 = 2 서비스 + gateway, 정본 settings.gradle.kts):
#       order-service, operation-service, gateway-service
#       (shared-common 은 모듈이 아니라 composite build 로 합성되는 별도 라이브러리)

# 프론트
cd frontend && npm ci
npx tsc -p tsconfig.app.json --noEmit               # 타입체크
npx vitest run                                      # 테스트
npm run build && npm run preview                    # 프로덕션 빌드 확인(/admin 직접진입 재현)

# 하네스 게이트
node --test "scripts/harness/test/*.test.mjs"

# Docker
cd frontend && npm run build && cd ..               # compose 의 frontend 는 dist 를 마운트한다
docker compose up -d                                # PG 2종 · ES · Redpanda · Redis · pgbouncer · 앱 3개 · frontend · 관측 7종
docker build --build-arg MODULE=<service> -t shop-<name> .   # 컨테이너 이미지 (MODULE 로 서비스 지정)
```

> `bootRun` 은 `../.env` 를 자동으로 읽지 못한다 — 필요한 env 는 `--args` 또는 System property 로 주입.

## 인프라

- 컨테이너: Docker Compose(로컬), Kubernetes(운영). 리버스 프록시: gateway-service.
- 모니터링: Prometheus + Micrometer + Grafana + OTLP. 메시지: Redpanda(Kafka 호환).
- DB-per-service: order = `inter`(스키마 `opslab`) / operation = `lemuel_operation`.
  order 만 DB명이 환경별로 갈린다 — compose `inter` / 로컬 기본 `opslab`.

## 브랜치·CI

- **메인 라인**: `develop` → `main`. main 은 보호 브랜치(PR 필수, squash 만, **필수 CI 6종** —
  목록은 [`../CLAUDE.md`](../CLAUDE.md) 작업 프로토콜 절).
- **백엔드 CI 는 모듈 매트릭스로 분할된다** — 변경된 모듈만 돌고, 집계 잡이 SBOM·Trivy·Sonar·
  커버리지 코멘트를 한 번만 수행한다.

  **집계 잡 이름(`Backend - Build/Test/JaCoCo/SonarCloud`)은 바꾸지 말 것** — ruleset 의 필수 상태
  체크로 등록된 문자열이고, 매트릭스 잡 이름은 가변이라 필수로 걸 수 없다. 또한 집계 잡에는
  `always()` + needs 결과 명시 검사가 걸려 있다: 기본 동작인 skip 은 필수 체크에서 통과로
  취급되어, 모듈 하나가 깨져도 게이트가 조용히 사라지기 때문이다.

  **잡을 더 쪼개지 않은 이유**: `jacocoTestCoverageVerification`(LINE 90%)이 모듈 단위라, 한 모듈의
  테스트를 CI 잡 여러 개로 나누면 각 샤드가 부분 커버리지만 갖게 되어 전부 게이트에 걸린다.
  우회하려면 `.exec` 를 아티팩트로 모아 병합 후 검증하는 배선이 필요한데, 로컬 검증이 불가능해
  CI 에서만 확인 가능해진다.

- **`cancelled` 는 통과가 아니다**: develop 은 최신 커밋이 이기므로 중간 커밋의 실행은 취소되는데
  빨간 X 가 남지 않는다. 판정 유무는 `node scripts/harness/ci-verdict.mjs [sha]` 로 체크 단위 확인한다.
