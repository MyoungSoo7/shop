# 모듈 구조 (Module Structure)

> 저장소 전체 디렉토리·모듈 구조의 정본. 서비스 책임·API 는 [`SPEC.md`](SPEC.md),
> 아키텍처 개요·패턴은 [`ARCHITECTURE.md`](ARCHITECTURE.md), 에이전트 지침은 [`CLAUDE.md`](CLAUDE.md) 참조.

## Gradle 멀티모듈 — 서비스 2 + Gateway + shared-common

```
shop/                                    # 모노레포 루트
├── settings.gradle.kts                  # 3 모듈 선언 (shared-common 은 composite build)
├── build.gradle.kts                     # 부모 빌드 (subprojects 공통 설정, JaCoCo LINE 90% 게이트)
├── docker-compose.yml                   # PG 2종 · ES · Redpanda · Redis · pgbouncer · 앱 3개 · frontend · 관측 7종
├── Dockerfile                           # MODULE 빌드 인자 파라미터화 (JVM 서비스 공용)
│
├── shared-common/                       # 📦 버전드 플랫폼 라이브러리 (java-library, ADR 0021)
│   ├── src/main/java/.../common/
│   │   ├── audit/                       # 감사 로그 (AuditLogger, AuditContext)
│   │   ├── config/jwt/                  # JWT 검증, SecurityConfig
│   │   ├── observability/               # MDC, TraceId 필터, PII 마스킹
│   │   ├── exception/                   # 공통 예외 (BusinessException 등)
│   │   ├── outbox/                      # Outbox 패턴 (이벤트 발행, 멱등 컨슈머, DLT 배선)
│   │   ├── money/                       # 금액 VO·라운딩 (BigDecimal 강제)
│   │   ├── ledger/                      # 복식부기 공통 (균형 팩토리)
│   │   ├── opssignal/                   # 운영 신호 발행 (절대 throw 금지, fire-and-forget)
│   │   ├── ratelimit/                   # Bucket4j 기반 rate limiting
│   │   ├── pdf/                         # iText PDF 유틸
│   │   ├── autoconfigure/               # 공통 자동구성
│   │   └── log/                         # 로깅 공통
│   ├── src/main/resources/kafka/topic-catalog.json     # ★ 토픽 전송 속성 정본 (21토픽, ADR 0035)
│   └── src/testFixtures/resources/contracts/events/    # ★ 이벤트 계약 정본 (20토픽 JSON Schema+샘플, ADR 0024)
│
├── order-service/                       # 🛒 Commerce (8088, DB inter / 스키마 opslab)
│   └── src/main/java/github/lms/lemuel/
│       ├── user/ order/ cart/ payment/ product/ category/ coupon/ review/ shipping/
│       ├── point/                       # 포인트 원장 — 계정·로트(FEFO)·홀드. 결제의 POINT 텐더가 부른다
│       ├── giftcard/                    # 기프트카드 원장 — 발행·등록·사용·복원·소멸
│       ├── bulkorder/                   # 대량주문 초안(업로드 → 검증 → 확정). 양식은 데이터로 둔다
│       ├── sellertier/                  # 셀러 등급 산정·재산정 (ADR 0031)
│       ├── organization/                # 조직·멤버십 슬라이스 (ADR 0042 흡수)
│       │                                #   발행 전용 4토픽 — 컨슈머 0 이 이 슬라이스의 설계다.
│       │                                #   경계는 OrganizationArchitectureTest 가 강제(order 의 다른 도메인 import 금지)
│       ├── menu/ rbac/ commoncode/      # 관리자 시스템 — 네비 정본(menus)·역할/권한·공통코드
│       ├── auditconsole/                # /admin/audit-logs — 감사 로그 조회(shared-common common.audit 재사용)
│       ├── game/                        # 부가 콘텐츠(오목·바둑) — 커머스 도메인 밖
│       ├── config/                      # 스캔·JPA·보안·파티션 유지보수 등 모듈 공용 인프라
│       └── web/                         # 공통 웹 어댑터 유틸
│
├── operation-service/                   # 🖥️ Operation (8092/mgmt 8093, lemuel_operation)
│   └── src/main/java/github/lms/lemuel/operation/
│       ├── incident/ signal/ anomaly/   # 관제 — 인시던트 라이프사이클 · 신호 5분 버킷 · z-score 이상탐지
│       ├── notification/                # 알림 팬아웃·푸시 SSE `/api/notifications/stream` (ADR 0041 흡수)
│       │                                #   자체 저장소 없음 — 수신함이 아니라 스트림이다
│       ├── board/                       # 📋 메타 주도 게시판 (ADR 0043 흡수): `board_definitions` 1행 = 게시판 1개,
│       │                                #   프론트 단일 라우트 `/boards/:boardKey` 가 스킨(LIST/GALLERY/FAQ/QNA)으로 렌더.
│       │                                #   발행 0·소비 0 — 권한은 역할 allowlist, 메뉴 등록은 관리 화면이 order `/admin/menus` 직접 호출
│       ├── education/                   # 🎓 과정·차시·게시 상태·ADMIN 콘텐츠 관리 (ADR 0043 흡수).
│       │                                #   CoursePublished Outbox — operation 의 유일한 발행 경로
│       └── config/                      # 모듈 공용 인프라(Clock·파티션 유지보수·Kafka 배선)
│
└── gateway-service/                     # 🚪 API Gateway (8080) — 라우팅만 (자체 인증 필터 없음)
```

- 각 서비스 내부는 헥사고날 고정 골격: `domain/` · `application/port/{in,out}·service/` · `adapter/{in,out}/`.
- 흡수된 슬라이스는 **프로세스와 DB 만 사라졌다.** REST 경로와 이벤트 계약은 불변이고,
  경계는 각 `*ArchitectureTest` 가 계속 강제한다.

## 부속 디렉토리

```
├── frontend/                            # ⚛️ React 19(Vite) 쇼핑/관리자 프론트 — nginx 프록시로 gateway 연동
├── docs/                                # 📚 ADR(adr/) · PRD(plan/prd/) · 러너북(plan/runbook/) · DEVELOPMENT
├── monitoring/                          # 📊 Prometheus·Grafana 대시보드·alert rules
├── load-test/                           # 🔥 k6 부하 시나리오
├── scripts/harness/                     # 🛡️ 저장소 가드(guard.mjs)·게이트 테스트·git hook 설치·텔레메트리
├── scripts/{config,db,sim}/             # 설정 고아 파라미터 감사 · 마이그레이션 검증 · 주문 시뮬레이터
├── k8s/                                 # ☸️ 운영 매니페스트 일부 (정본 배포 배선은 helm-deploy 레포 + ArgoCD)
├── .claude/                             # 🤖 에이전트 자산 — 스킬·커맨드·에이전트 정의 (플러그인 독립, 저장소 추적)
└── gradle/                              # Gradle wrapper   ※ `https/`(로컬 TLS)는 .gitignore — 저장소 미추적
```
