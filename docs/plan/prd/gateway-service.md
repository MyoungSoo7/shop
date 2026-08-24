# PRD — API 게이트웨이 (gateway-service)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 `settlement-core.md`·`external-data-commondata.md` 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                       |
> | --------- | ------------------------------------------------------------------------------------------ |
> | 대상 범위 | `gateway-service`(8080, DB 없음) — Spring Cloud Gateway(WebFlux) 라우팅 표면 전체         |
> | 역산 기준 | 2026-08-13 `develop` 브랜치                                                              |
> | 근거      | 프로덕션 코드 2파일 163줄(앱 클래스 12줄 + 라우트 YAML 138줄 · 라우트 17건), 테스트 1클래스 2메서드, compose 배선, nginx 프론트 프록시 |
> | 범위 밖   | 각 백엔드 서비스의 인증·인가·도메인 규칙(각 서비스 소관) · k8s Ingress(helm-deploy 레포)  |
> | 관련 문서 | [`../../../SPEC.md`](../../../SPEC.md) · `../polyglot-services.md` · `../seeds/gateway-service-routing.seed.yaml` |

---

## 1. 배경과 문제

브라우저 한 대가 **프로세스 23개의 포트를 알고 있어야 한다면**(인벤토리 17 서비스 = JVM 16 + 폴리글랏 부속 1묶음,
그 묶음이 실제로는 7 프로세스다) 프론트엔드는 배포 토폴로지에 묶인다. 서비스가
하나 늘 때마다 프론트 빌드가 바뀌고, CORS 오리진이 늘고, 셀러 화면 하나가 5개 포트를 동시에 호출한다.

| 문제                | 구체적 손상                                                                    |
| ------------------- | ------------------------------------------------------------------------------ |
| **토폴로지 누출**   | 프론트가 포트·호스트를 알면 서비스 이전·분리 때마다 프론트를 다시 배포해야 한다 |
| **오리진 폭발**     | 서비스마다 다른 오리진 → CORS·쿠키·CSP 설정이 서비스 수만큼 늘어난다            |
| **노출면 무통제**   | 각 서비스의 `/admin/**` 수집 트리거가 외부에서 그대로 보인다                    |

gateway-service 는 **하나의 오리진(8080)으로 전부를 받아 경로로 백엔드를 고르는** 리액티브 라우터다.
핵심 설계 판단은 하나다 — **게이트웨이는 라우팅만 한다. 인증하지 않는다.**

## 2. 목표 / 비목표

### 2.1 목표

| #  | 목표                                       | 성공 기준                                                     |
| -- | ------------------------------------------ | ------------------------------------------------------------- |
| G1 | 프론트는 단일 오리진만 안다                | 프론트 코드에 백엔드 포트가 없다(경로만 있다)                 |
| G2 | 백엔드 주소는 배포 시점에 주입된다         | 라우트 URI 전부 `${*_SERVICE_URI:localhost:포트}` 환경변수     |
| G3 | 외부에 보일 경로만 보인다                  | 위성 서비스의 `/admin/**` 수집 트리거는 라우트에 없다          |
| G4 | 백엔드 컨트롤러 경로를 게이트웨이가 안 바꾼다 | 프리픽스 무변경 전달(예외 2건은 명시적 `RewritePath`)         |

### 2.2 비목표 (의도적으로 하지 않는 것)

| #  | 비목표                     | 이유                                                                       |
| -- | -------------------------- | -------------------------------------------------------------------------- |
| N1 | 자체 인증·인가 필터        | JWT 검증은 각 서비스의 `shared-common` 보안 체인이 한다 — 이중 검증 금지    |
| N2 | 레이트리밋                 | Bucket4j 가 각 서비스에 붙어 있다(주체·비용을 아는 쪽이 판단)               |
| N3 | 응답 집계(BFF)             | 라우팅 전용. 화면 조립은 프론트 몫                                          |
| N4 | 서비스 디스커버리          | 정적 라우트 + 환경변수. Eureka/Consul 없음                                  |
| N5 | 자체 DB·상태               | 무상태. 세션·캐시 없음                                                      |

## 3. 사용자

| 사용자             | 무엇을 위해 쓰는가                                            |
| ------------------ | ------------------------------------------------------------- |
| **프론트엔드 SPA** | nginx 를 거쳐 단일 오리진으로 전 API 호출                     |
| **운영자**         | `/admin/**` 운영 콘솔(주문·정산·예치금) 접근                  |
| **외부/데모**      | 공개 조회 API(재무·경제·시세·공공데이터) 인증 없이 조회       |

## 4. 제품 범위 — 기능 맵

| 영역        | 기능                                                                       |
| ----------- | -------------------------------------------------------------------------- |
| 라우팅      | 경로 predicate 17건 → 백엔드 15 Java + 폴리글랏 2                          |
| 경로 변환   | `RewritePath` 2건(폴리글랏 프리픽스 제거)                                  |
| 노출 통제   | 위성 서비스 `/admin/**` 미등록으로 외부 차단                               |
| 운영        | actuator health·info·metrics·prometheus, graceful shutdown                 |
| 공급망 보안 | `bcprov-jdk18on` 전이 버전 constraint(CVE-2025-14813)                      |

## 5. 핵심 유스케이스

### UC-1. 프론트가 포트를 모른 채 전 서비스를 호출한다

1. 브라우저가 `/api/settlements/...` 를 nginx(3000) 로 보낸다.
2. nginx 가 `gateway-service:8080` 으로 프록시한다(변수 `proxy_pass` + 도커 DNS 재해석 — stale IP 방지).
3. 게이트웨이가 경로 predicate 로 `settlement-service` 라우트를 골라 `${SETTLEMENT_SERVICE_URI}` 로 전달한다.
4. 프리픽스는 그대로다 — 백엔드 컨트롤러 매핑과 1:1이라 게이트웨이 설정이 백엔드 리팩터링을 강제하지 않는다.

### UC-2. 수집 트리거가 외부에 안 보인다

1. external-data(financial·economics·market·commondata)·company 는 **공개 조회 경로만** 라우트에 있다(`/api/{도메인}/**`).
2. 각 서비스의 `/admin/**`(DART·ECOS·KRX·포털 수집 트리거)은 predicate 에 없다 → 게이트웨이에서 404.
3. 반대로 order·settlement·deposit 의 `/admin/**` 은 **운영 콘솔이 실제로 쓰는 화면**이라 명시적으로 열거돼 있다.

### UC-3. 폴리글랏 SSE 를 같은 오리진으로 구독한다

1. 프론트는 `/api/market-stream/stream/{code}` 로 `EventSource` 를 연다.
2. 게이트웨이가 `RewritePath` 로 프리픽스를 벗겨 Go 서비스의 실경로 `/stream/{code}` 로 전달한다.
3. `/ws/**`(WebSocket)는 라우트에 없다 — 프론트는 SSE 만 쓴다.

## 6. 기능 요구사항

| FR   | 요구사항                                                        | 강제 지점                                          |
| ---- | --------------------------------------------------------------- | -------------------------------------------------- |
| FR-1 | 라우트는 17건이며 백엔드 URI 는 전부 환경변수로 주입된다        | `application.yml` routes                           |
| FR-2 | 게이트웨이는 경로 프리픽스를 바꾸지 않는다                      | 필터 미사용(예외 2건만 `RewritePath`)              |
| FR-3 | 폴리글랏 2종은 프리픽스를 벗겨 전달한다                         | `RewritePath` (market-stream·notification)         |
| FR-4 | notification 은 스트림 **단일 경로**만 노출한다(와일드카드 금지) | `Path=/api/notifications/stream` (정확 일치)      |
| FR-5 | 리액티브 웹 스택으로 기동한다                                   | `spring.main.web-application-type: reactive`       |
| FR-6 | 컨텍스트 부팅 + 라우트 로드가 검증된다                          | `GatewayServiceApplicationTest`                    |
| FR-7 | 전이 의존 `bcprov` 는 CVE 수정판으로 고정한다                   | `build.gradle.kts` constraint 1.84                 |

## 7. 도메인 규칙 (BR)

| BR   | 규칙                                                                                                   | 근거                                       |
| ---- | ------------------------------------------------------------------------------------------------------ | ------------------------------------------ |
| BR-1 | **게이트웨이는 신뢰 경계가 아니다** — 인증은 각 서비스가 한다. 게이트웨이 통과 = 인증 통과가 아니다     | 자체 필터 0개                              |
| BR-2 | **라우트 목록이 노출면이다** — 등록하지 않은 경로는 존재하지 않는 것과 같다                             | 위성 `/admin/**` 미등록                    |
| BR-3 | **권한 등급이 다른 경로는 합치지 않는다** — deposit 의 `/api`(읽기)와 `/admin`(잔고 이동)을 한 와일드카드로 묶지 않는다 | settlement 라우트 주석(ADR 0039 흡수 후) |
| BR-4 | **프리픽스는 백엔드가 정한다** — order 처럼 `/api` 유무가 혼재해도 게이트웨이가 통일하지 않는다         | order 라우트 주석                          |

## 8. 데이터 모델

**없음.** 무상태 라우터 — DB·캐시·세션 모두 없다.

## 9. 인터페이스

### 9.1 라우트 표 (9건 — 2026-08-25 통합 사이클 반영)

| # | 라우트 id | 기본 URI | 경로 predicate(요약) |
|---|---|---|---|
| 1 | `order-service-orders` | 8088 | `/auth`·`/users`·`/orders`·`/payments`·`/coupons`·`/reviews`·`/games`·`/categories`·`/memberships`·`/products` + `/api/{products,categories,tags,payments,menus,points,gift-cards,bulk-orders,organizations}` + `/admin/{categories,products,pg,menus,common-codes,rbac,settlement-projection,payment-expiry,stock-reclaim,seller-tiers,shipments,shipping-policies,option-catalog,display-sections,points,gift-cards,audit-logs,members,reviews,coupons,refunds}` + `/display-sections` — `/api/organizations/**` 는 ADR 0042 흡수로 합류 |
| 2 | `settlement-service` | 8082 | `/settlements` + `/api/{settlements,ledger,reports,tax-invoices,deposits}` + `/api/seller/bank-account` + `/admin/{settlements,payouts,seller-bank-accounts,chargebacks,pg-reconciliation,reconciliation,integrity,recoveries,monthly-closing,outbox,dlq,backfill,event-track,ledger-periods,seller-tax-profiles,tax,commission-rates,audit-trail,deposits}` |
| 3 | `finance-service` | 8084 | `/loans/**`·`/api/investment/**`·`/api/account/**`·`/api/banking/**`·`/api/cards/**`·`/admin/expense-receipts/**`·`/api/insurance/**` (ADR 0039 3단계 — loan·investment·account·card·insurance 5슬라이스 경로, URL 불변) |
| 4 | `external-data-service` | 8086 | `/api/financial/**`·`/api/economics/**`·`/api/market/**`·`/api/common-data/**` (ADR 0038 통합 — 종전 4개 라우트) |
| 5 | `company-service` | 8090 | `/api/company/**` |
| 6 | `ai-chat` (settlement-service 의 ai 슬라이스) | 8082 | `/api/ai/**` (ADR 0040 흡수 — URL 불변, SSE 라 별도 라우트 유지) |
| 7 | `operation-service` | 8092 | `/api/ops/**` + `/api/boards/**`·`/admin/boards/**`·`/admin/education/**` (ADR 0043 흡수 — board·education 슬라이스 경로 합류, URL 불변) |
| 8 | `market-stream-service` | 8110 | `/api/market-stream/**` → `RewritePath` → `/{segment}` |
| 9 | `notification-stream` (operation-service 의 notification 슬라이스) | 8092 | `/api/notifications/stream`(정확 일치 — ADR 0041 흡수 후 실경로와 같아 RewritePath 없음) |

> Java 6개 서비스 **전부** 라우팅된다. 흡수된 슬라이스 경로는 전부 URL 불변으로 소유 서비스 라우트에
> 합류했다(deposit → settlement 2번, organization → order 1번, board·education → operation 7번).
> 폴리글랏에서 라우팅되는 것은 market-stream 1종뿐이다.
> education 은 공개 표면이 없어 `/admin/education/**` 하나만 등록돼 있다(ADMIN 콘텐츠 관리 전용).

### 9.2 운영 엔드포인트

| 경로                      | 설명                                          |
| ------------------------- | --------------------------------------------- |
| `/actuator/health`        | liveness·readiness probe, `when-authorized` 상세 |
| `/actuator/prometheus`    | 메트릭 스크레이프                             |

### 9.3 이벤트

**없음.** Kafka 의존 자체가 빌드에 없다.

## 10. 비기능 요구

| NFR   | 요구                       | 현재 상태                                                    |
| ----- | -------------------------- | ------------------------------------------------------------ |
| NFR-1 | 무상태 · 수평 확장 가능    | DB·세션 없음                                                 |
| NFR-2 | 우아한 종료                | `server.shutdown: graceful`                                  |
| NFR-3 | 컨테이너 메모리 인지       | `-XX:MaxRAMPercentage=75` (compose `JAVA_OPTS`)              |
| NFR-4 | CRITICAL CVE 0             | Trivy 이미지 스캔 게이트 — `bcprov` 1.84 constraint 로 해소   |
| NFR-5 | 커버리지 게이트            | 프로덕션 로직이 사실상 YAML 이라 라우트 로드 검증이 실질 게이트 |

## 11. 배치

**없음.** 스케줄러·배치 작업 0.

## 12. 역산에서 드러난 격차

### G-1. notification 라우트는 있으나 compose 에서 도달 불가 ★

`application.yml` 에 `/api/notifications/stream` 라우트가 있고 기본 URI 는 `${NOTIFICATION_SERVICE_URI:http://localhost:8130}`
이다. 그런데 **`docker-compose.yml` 에 `notification-service` 컨테이너 정의가 없고**, gateway 컨테이너의
`environment` 에도 `NOTIFICATION_SERVICE_URI` 가 없다(실측: `MARKET_STREAM_SERVICE_URI` 는 있음). 따라서
compose 로 띄우면 게이트웨이는 **자기 컨테이너의 localhost:8130** 으로 프록시를 시도한다 — 라우트는 존재하지만
연결되는 백엔드가 없다. `CLAUDE.md` 는 두 폴리글랏 모두 "gateway 라우팅 + compose 배선"이라고 기술하는데,
compose 배선은 market-stream 한쪽만 사실이다.

### G-2. notification SSE 는 nginx 에서 버퍼링·60초 타임아웃에 걸린다 ★

`frontend/nginx.compose.conf` 는 SSE 경로마다 `proxy_buffering off` 전용 location 을 둔다 — `/api/ai/`(챗봇),
`/api/market-stream/`(시세) 2건. `/api/notifications/stream` 은 이 전용 location 이 **없어** 범용 regex
location(`^/(auth|api|admin|...)`)에 잡힌다. 거기는 `proxy_buffering` 기본값(on) + `proxy_read_timeout 60s` 라,
G-1 이 해소돼도 브라우저는 이벤트를 묶여서 받고 60초마다 끊긴다.

### G-3. 라우트 테스트가 17건 중 5건만 어서트한다

`GatewayServiceApplicationTest.routesAreConfigured` 는 `order-service-orders`·`settlement-service`·`ai-chat`·
`loan-service`·`operation-service` 5개 id 만 확인한다. 나머지 15개 라우트는 **삭제되거나 오타가 나도 테스트가
통과한다.** 라우트 목록이 이 서비스의 유일한 프로덕션 로직인데 그 대부분이 회귀 보호를 받지 못한다.

### G-4. 경로 화이트리스트가 수기 유지되는 긴 목록이다

order 라우트 predicate 하나에 경로 32개, settlement 에 22개가 열거돼 있다. 백엔드에 컨트롤러가 추가되면
여기에 손으로 넣어야 하고, 안 넣으면 **컴파일도 테스트도 통과한 채 런타임 404** 가 난다(같은 함정을
`msa-service-wiring` 스킬이 다룬다). 반대로 nginx 는 이미 이 문제를 겪고 allowlist 를 버리는 방향으로
바꿨다(주석 :31-34) — 게이트웨이만 allowlist 로 남아 있다.

### G-5. `prod` 프로파일이 존재하지 않는다

compose 는 `SPRING_PROFILES_ACTIVE: prod` 를 주입하는데 `application-prod.yml` 은 없다. 지금은 무해하지만
(프로파일 없는 기동은 정상), "prod 라 뭔가 다르게 동작한다"는 오해를 부른다.

### G-6. 기동 순서 의존이 order-service 하나뿐이다

`depends_on` 은 `order-service: service_healthy` 만 건다. 나머지 15개 서비스가 아직 안 떴을 때 게이트웨이는
정상 기동하고 커넥션 오류를 그대로 클라이언트에 노출한다. 헬스체크에 백엔드 도달성이 반영되지 않는다.

### G-7. CORS 설정이 게이트웨이에 없다

동일 오리진(nginx → gateway) 전제라 지금은 필요 없지만, 게이트웨이를 브라우저가 직접 호출하는 배포
형태(vite dev 프록시 우회 등)에서는 CORS 처리 주체가 각 백엔드로 흩어진다.

## 13. 추적 항목

| #   | 항목                                                       | 상태                     |
| --- | ---------------------------------------------------------- | ------------------------ |
| T-1 | notification-service compose 정의 + `NOTIFICATION_SERVICE_URI` | 없음 (G-1)            |
| T-2 | nginx `/api/notifications/` 무버퍼 location                 | 없음 (G-2)               |
| T-3 | 라우트 id 전수 어서트로 테스트 강화                         | 5/18 만 검증 (G-3)       |
| T-4 | 경로 화이트리스트 유지 전략(allowlist → 프리픽스 규약)      | 수기 유지 중 (G-4)       |
| T-5 | `application-prod.yml` 도입 또는 프로파일 주입 제거         | 불일치 (G-5)             |
