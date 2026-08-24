# PRD — 알림 팬아웃·푸시 (operation-service `notification` 슬라이스)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 `settlement-core.md`·[`gateway-service.md`](gateway-service.md) 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                       |
> | --------- | ------------------------------------------------------------------------------------------ |
> | 대상 범위 | `operation-service` 의 `notification` 슬라이스(8092, Java 25 / Boot 4, **자체 저장소 없음**) — 이벤트 알림 팬아웃 + SSE 푸시 |
> | 역산 기준 | 2026-08-25 `develop` 브랜치 ([ADR 0041](../../adr/0041-notification-absorbed-into-operation.md) 흡수 직후) |
> | 근거      | 프로덕션 Java 33파일, 테스트 9클래스(90 tests), 구독 4토픽, 채널 4종, `application.yml` |
> | 범위 밖   | 발행측 서비스의 이벤트 계약(각 서비스 소관) · 실 SMTP/Slack 운영 · 알림 이력 영속화       |
> | 관련 문서 | [`operation-service.md`](operation-service.md) · [`gateway-service.md`](gateway-service.md) · `../seeds/operation-service-notification-fanout.seed.yaml` · `../../sse.md` |

---

## 1. 배경과 문제

정산이 확정되고 결제가 잡히고 투자가 체결돼도 **셀러는 화면을 새로고침해야 안다.** 각 Java 서비스가 자기
알림을 직접 보내면 세 가지가 무너진다.

| 문제                | 구체적 손상                                                                        |
| ------------------- | ---------------------------------------------------------------------------------- |
| **채널 로직 산재**  | 서비스마다 SMTP·Slack 설정과 재시도 코드를 다시 쓴다                               |
| **중복 발송**       | Kafka 재배달·재시작마다 같은 알림이 다시 나간다 — 수신자에겐 사고로 보인다         |
| **유실이 안 보인다** | 파싱 실패·전 채널 실패를 catch 해서 로그만 남기면 오프셋은 커밋되고 메시지는 사라진다 |

알림 슬라이스는 **도메인 이벤트 하나를 받아 활성 채널 전부로 동시에 팬아웃하는** 단일 지점이다.
핵심 설계 판단은 하나다 — **삼키지 않는다.** 도달하지 못한 알림은 반드시 DLT 에 남는다.

## 2. 목표 / 비목표

### 2.1 목표

| #  | 목표                                     | 성공 기준                                                              |
| -- | ---------------------------------------- | ---------------------------------------------------------------------- |
| G1 | 이벤트 1건이 모든 활성 채널에 동시 도달  | 가상 스레드 executor 팬아웃, 채널별 독립 타임아웃·재시도               |
| G2 | 재배달이 중복 발송을 만들지 않는다       | `eventId` 선점(`DedupeStore.markIfFirst`) — 중복은 dispatch 진입 전 스킵 |
| G3 | 도달하지 못한 알림이 사라지지 않는다     | 파싱 실패·전 채널 실패 → 예외 → `<topic>.DLT` 격리                     |
| G4 | 브라우저가 알림을 즉시 받는다            | `GET /api/notifications/stream` SSE + `Last-Event-ID` 재개              |
| G5 | 푸시 스트림이 남의 알림을 흘리지 않는다  | 수신자 키를 **검증된 JWT 에서만** 파생(요청 파라미터 불신)             |
| G6 | 브로커 없이도 기동·데모된다              | `app.kafka.enabled=false` 기본 + Kafka 헬스 인디케이터 비활성          |

### 2.2 비목표 (의도적으로 하지 않는 것)

| #  | 비목표                        | 이유                                                                 |
| -- | ----------------------------- | -------------------------------------------------------------------- |
| N1 | 알림 이력 영속화              | 무영속 MVP — DB 없음. 재생 창은 프로세스 메모리                      |
| N2 | 수신자 선호·구독 관리         | 이벤트 페이로드의 주소 필드로만 라우팅                               |
| N3 | ~~`shared-common` 재사용 회피~~ | **뒤집혔다** — 흡수로 shared-common 을 의존하게 되어 Kafka 에러 핸들링·JWT 를 공용 구현으로 대체했다(사본 삭제) |
| N4 | 부분 성공의 재전송            | 이미 도달한 채널이 있으면 DLT 로 안 보낸다(replay 시 중복 발송 방지) |
| N5 | Outbox 발행                   | 소비 전용. 이 서비스는 Kafka 에 쓰지 않는다(DLT 격리 publish 제외)   |

## 3. 사용자

| 사용자           | 무엇을 위해 쓰는가                                        |
| ---------------- | --------------------------------------------------------- |
| **셀러/사용자**  | 브라우저에서 정산 확정·결제·투자 체결 알림을 실시간 수신  |
| **운영자(ADMIN)** | 수신자 필드가 없는 이벤트를 ops 메일함(`ops@lemuel`)으로 관측 |
| **발행 서비스**  | 자기 도메인 이벤트만 내면 알림은 이 서비스가 책임진다     |

## 4. 제품 범위 — 기능 맵

| 영역     | 기능                                                            |
| -------- | --------------------------------------------------------------- |
| 수신     | Kafka 4토픽 구독 → 템플릿 분류 → 알림 생성                      |
| 팬아웃   | 활성 채널 동시 발송(log·sse 항상 / slack·email 조건부)          |
| 멱등     | `eventId` TTL 선점(기본 30분), 헤더 `event_id` 우선             |
| 격리     | 유독 메시지·전 채널 실패 → `<topic>.DLT`, 재시도 2s×3           |
| 푸시     | SSE 스트림, 하트비트 15s, `Last-Event-ID` 재개, 재생 창 100건/수신자 |
| 관리     | REST 발송·데모, actuator health·prometheus                      |

## 5. 핵심 유스케이스

### UC-1. 정산 확정 이벤트가 셀러에게 도달한다

1. `lemuel.settlement.confirmed` 를 수신한다(헤더 `event_id` = Outbox UUID).
2. `NotificationTemplate.fromEvent` 가 분류(`SETTLEMENT_CONFIRMED`)하고 수신자를 `recipient → sellerId → userId → accountId → ops@lemuel` 순으로 고른다.
3. `DedupeStore` 가 `eventId` 를 선점한다 — 이미 본 id 면 여기서 끝(스킵).
4. 활성 채널 전부에 **동시** 발송. 채널마다 3초 타임아웃 × 3회(50/100/200ms 백오프).
5. `sse` 채널이 허브에 publish → 해당 수신자로 열린 브라우저 연결에 즉시 밀어 넣는다.

### UC-2. 아무 채널에도 도달하지 못한 알림이 격리된다

1. 전 채널이 실패하면 `DispatchResult.anySucceeded == false`.
2. 리스너가 `NotificationDispatchFailedException`(=`IllegalStateException`)을 던진다.
3. 에러 핸들러가 이를 **재시도 불가**로 분류해 즉시 `<topic>.DLT` 로 복사한다 — 채널은 이미 자체 재시도를 소진했고, dedupe 가 선점돼 있어 Kafka 재시도는 no-op 로 "성공"처럼 보이기 때문이다.
4. 파티션은 프로듀서가 고른다(`-1`). 소스 토픽 파티션 번호를 고정하면 DLT 파티션 수가 모자랄 때 격리 publish 자체가 실패한다(실측: `lemuel.payment.captured` 6파티션 vs DLT 3파티션).

### UC-3. 브라우저가 끊겼다 붙어도 놓친 알림을 받는다

1. `EventSource` 가 `/api/notifications/stream?token=<JWT>` 로 연결한다(브라우저는 헤더를 못 실어 쿼리 파라미터 허용, 헤더가 있으면 헤더 우선).
2. 서버가 JWT 를 검증해 수신자 키 집합을 만든다 — `sub`(이메일), `uid`, 그리고 **ADMIN 일 때만** ops 메일함.
3. 재연결 시 브라우저가 자동으로 보내는 `Last-Event-ID` 이후 이벤트를 재생 창에서 순서대로 재전송한 뒤 라이브로 이어진다.
4. 구독 등록과 백로그 적재가 **하나의 락 안에서** 일어나 재개에 구멍이 없다.

## 6. 기능 요구사항

| FR    | 요구사항                                                              | 강제 지점                                        |
| ----- | --------------------------------------------------------------------- | ------------------------------------------------ |
| FR-1  | 4토픽을 구독한다                                                      | `NotificationDomainEventListener` `@KafkaListener` |
| FR-2  | `eventId` 는 헤더 → payload(`eventId`/`id`) → key 순으로 결정한다      | 리스너 :82                                       |
| FR-3  | 중복 `eventId` 는 발송 없이 스킵한다                                  | `NotificationDispatcher` 멱등 게이트             |
| FR-4  | 파싱 불가 페이로드는 재시도 없이 DLT 로 간다                          | `UnparseableEventPayloadException`(IAE)          |
| FR-5  | 전 채널 실패는 DLT, 부분 성공은 DLT 로 보내지 않는다                  | 리스너 :101-105                                  |
| FR-6  | 채널은 자기 `enabled` 로 자기 참여를 결정한다                         | `NotificationChannel.enabled`                    |
| FR-7  | 수신자 키는 검증된 JWT 에서만 파생한다                                | `JwtSubscriberIdentityResolver`                  |
| FR-8  | 서명키 미설정 시 스트림은 503, 토큰 무효는 401                        | `StreamNotConfigured`/`StreamUnauthorized` 핸들러 |
| FR-9  | 재생 창·구독 메일박스는 상한이 있다                                   | 100/수신자, 10,000수신자, 200/구독자             |
| FR-10 | 도메인은 blank 수신자·제목을 거부한다                                 | `NotificationInvariantViolationException`        |

## 7. 도메인 규칙 (BR)

| BR   | 규칙                                                                                                          | 근거                                  |
| ---- | ------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| BR-1 | **삼키지 않는다** — 리스너는 예외를 잡지 않는다. 잡으면 오프셋이 커밋되고 유독 메시지가 사라진다               | `DomainEventListener` 클래스 주석     |
| BR-2 | **재시도가 고칠 수 없는 것은 재시도하지 않는다** — 파싱 실패·전 채널 실패는 즉시 격리                          | `addNotRetryableExceptions`            |
| BR-3 | **부분 성공은 실패가 아니다** — 한 채널이라도 도달했으면 replay 시 그 채널 중복을 만들지 않는다                | 리스너 :96-99                         |
| BR-4 | **푸시는 요청을 믿지 않는다** — 수신자 키를 파라미터에서 받으면 그 순간 IDOR 이다                              | `SubscriberIdentity` 주석             |
| BR-5 | **fail-closed** — 서명키가 없으면 스트림은 열리지 않는다(서비스는 계속 기동)                                  | `JwtSubscriberIdentityResolver`       |
| BR-6 | **수신자 없는 이벤트는 조용히 버리지 않는다** — ops 메일함으로 보내 눈에 띄게 한다                             | `OPS_FALLBACK_RECIPIENT`              |
| BR-7 | **느린 구독자가 발행을 막지 않는다** — 리스너 호출은 락 밖에서, 구독자별 메일박스로 순서 보장                  | `InMemoryNotificationStream` 설계 주석 |

## 8. 데이터 모델

**DB 없음.** 상태는 전부 프로세스 메모리다.

| 구조                        | 역할                | 상한                                    |
| --------------------------- | ------------------- | --------------------------------------- |
| `InMemoryTtlDedupeStore`    | eventId 멱등        | TTL 30분(기본), 1024건 초과 시 스윕     |
| `InMemoryNotificationStream` | 재생 창 + 구독자 색인 | 수신자당 100건 / 수신자 10,000 / 구독자 대기 200 |

## 9. 인터페이스

### 9.1 REST

| 메서드 | 경로                     | 인증        | 설명                                       |
| ------ | ------------------------ | ----------- | ------------------------------------------ |
| POST   | `/internal/notifications/send`   | 공유 시크릿 | 임의 알림 발송(게이트웨이 미노출)          |
| GET    | `/internal/notifications/demo`   | 공유 시크릿 | 샘플 알림 팬아웃(게이트웨이 미노출)        |
| GET    | `/api/notifications/stream`      | JWT 필수    | SSE 푸시 — 게이트웨이가 노출하는 유일 경로 |

에러 매핑: 도메인 검증 400 · 미인증 401 · 서명키 미설정 503(`STREAM_NOT_CONFIGURED`).

### 9.2 이벤트

| 방향 | 토픽                                                                                                     |
| ---- | -------------------------------------------------------------------------------------------------------- |
| 소비 | `lemuel.settlement.confirmed` · `lemuel.payment.captured` · `lemuel.payment.refunded` · `lemuel.investment.executed` (`lemuel.payment.confirmed` 는 발행자 소멸로 구독 해제 — 분류표만 유지) |
| 발행 | **없음** (DLT 격리 복사만 — `<topic>.DLT`)                                                               |

### 9.3 채널

| 채널    | 활성 조건                          | 실패 처리                     |
| ------- | ---------------------------------- | ----------------------------- |
| `log`   | 항상                               | —                             |
| `sse`   | 항상                               | 구독자 0명도 실패 아님        |
| `slack` | `SLACK_WEBHOOK_URL` 설정 시        | 비-2xx → 예외 → 재시도        |
| `email` | `MAIL_USERNAME`+`MAIL_PASSWORD` 시 | SMTP 예외 → 재시도            |

## 10. 비기능 요구

| NFR   | 요구                             | 현재 상태                                                        |
| ----- | -------------------------------- | ---------------------------------------------------------------- |
| NFR-1 | 브로커 없이 기동                 | 리스너 `@ConditionalOnProperty` OFF 기본 + Kafka 헬스 비활성      |
| NFR-2 | DLT 격리가 관측된다              | `notification.kafka.dlt.published` 카운터 + prometheus 레지스트리 |
| NFR-3 | 유휴 SSE 연결 유지               | 15초 하트비트 주석 프레임, 재연결 힌트 2s                        |
| NFR-4 | 한글 제목 깨지지 않음            | `text/event-stream;charset=UTF-8` 명시                           |
| NFR-5 | 컨테이너 비루트 실행             | Dockerfile non-root(`app`), JDK21 멀티스테이지                   |
| NFR-6 | ~~JDK 21 고정~~                  | **소멸** — Java 25 모듈로 흡수돼 별도 툴체인·`JAVA_HOME` 지정이 필요 없다 |

## 11. 배치

**없음.** 스케줄러는 SSE 하트비트 전용 단일 데몬 스레드뿐이다.

## 12. 역산에서 드러난 격차

### G-1. 푸시 스트림이 기본 배포에서 항상 503 이다 ★

`JwtSubscriberIdentityResolver` 는 `app.security.jwt.secret` 을 읽는데, **`application.yml` 에 이 키가 없다.**
기본값은 빈 문자열이라 `configured=false` → `/notifications/stream` 은 무조건 `503 STREAM_NOT_CONFIGURED` 를
낸다. 환경변수 플레이스홀더(`${JWT_SECRET:}` 등)도 선언돼 있지 않아, 운영자가 이 값을 넣으려면 스프링
릴랙스 바인딩 규칙(`APP_SECURITY_JWT_SECRET`)을 스스로 알아내야 한다. 설계는 fail-closed 로 옳지만
**현재 구성으로는 SSE 푸시 기능 전체가 꺼져 있다.**

> **해소(632091a1)** — `app.security.jwt.secret: ${JWT_SECRET:}` 를 `application.yml` 에 선언했다.
> 레포 공통 `JWT_SECRET` 을 그대로 쓰므로 게이트웨이가 발급한 토큰이 여기서 검증된다. 값이 비면
> 스트림만 꺼지고 부팅은 계속되는 fail-closed 동작은 유지. 로컬 compose 에서 JWT 로 스트림을 열어
> 데모 알림 수신까지 확인했다.
>
> **근본 해소(ADR 0041)** — 흡수하면서 리졸버가 shared-common 과 <b>같은 키</b>(`app.jwt.secret`)를 읽도록
> 바꿨다. operation-service 는 이 키가 없으면 아예 기동하지 않으므로(`${JWT_SECRET}` 필수), "선언을 빠뜨려
> 조용히 503" 이라는 형태의 결함이 구조적으로 불가능해졌다.

### G-2. 서비스가 compose 에 없고 게이트웨이가 도달하지 못한다 ★

`docker-compose.yml` 에 `notification-service` 컨테이너 정의가 없고, gateway 컨테이너에도
`NOTIFICATION_SERVICE_URI` 가 없다. 게이트웨이 라우트는 존재하므로 "배선됐다"고 읽히지만 실제로는
게이트웨이 자신의 `localhost:8130` 으로 프록시를 시도한다. 상세는 [`gateway-service.md`](gateway-service.md) G-1.

> **해소(632091a1)** — compose 에 `notification-service` 컨테이너와 gateway 의
> `NOTIFICATION_SERVICE_URI=http://notification-service:8130` 을 넣었다. 컨테이너 DNS 도달을
> 실기동으로 확인했다. k8s/helm 배포 경로는 여전히 미배선이다.
>
> **근본 해소(ADR 0041)** — 별도 컨테이너 자체가 사라졌다. 게이트웨이 라우트는 `OPERATION_SERVICE_URI` 를
> 가리키고, 그 URI 는 운영 콘솔(`/api/ops/**`)이 이미 쓰던 것이라 <b>배선 지점이 하나 줄었다</b>.
> k8s/helm 도 operation 차트에 얹히므로 별도 차트가 필요 없다.

### G-3. nginx 가 이 SSE 를 버퍼링하고 60초에 끊는다 ★

`/api/ai/`·`/api/market-stream/` 에는 `proxy_buffering off` 전용 location 이 있지만
`/api/notifications/stream` 에는 없었다. 이벤트가 묶여 도착하고 60초마다 끊겼다.
상세는 [`gateway-service.md`](gateway-service.md) G-2.

> **해소** — nginx 두 벌(`frontend/nginx.conf`·`nginx.compose.conf`)에 정확일치(`=`) location 을 넣었고,
> `sse-nginx-gate.test.mjs` 가 회귀를 CI 에서 막는다. 경로가 흡수 후에도 그대로라 이 배선은 손대지 않았다.

### G-4. 이벤트 소비가 기본 OFF 다

`app.kafka.enabled` 기본값이 `false` 다. "브로커 없이 기동"이라는 목표(G6)의 대가로, 이 값을 켜지 않으면
알림의 입력 소스가 없다 — REST `/internal/notifications/send` 만 동작하고, 그 경로는 게이트웨이에
노출돼 있지 않다(의도적).

> **완화(ADR 0041)** — operation-service 의 compose 블록은 이미 `APP_KAFKA_ENABLED: "true"` 를 주입한다
> (신호 컨슈머가 그 값을 쓰고 있었다). 즉 흡수와 동시에 알림 컨슈머도 켜진 상태로 배포된다 —
> 별도 서비스일 때는 그 값을 따로 넣어야 했다.

### G-5. 무인증 발송 경로가 존재한다

발송·데모 경로에는 사용자 단위 인가가 없다. 임의 수신자에게 임의 제목·본문 알림을 만들 수 있다.

> **완화(ADR 0041)** — 경로를 `/internal/notifications/**` 로 옮겨 shared-common 전역 체인의
> `/internal/**` + 공유 시크릿 필터(`InternalApiKeyFilter`) 게이트 아래로 넣었다. 방어가
> "게이트웨이에 라우트를 안 올린다" 하나에서 <b>둘</b>로 늘었다. 다만 키 미설정 개발 환경에서는
> 여전히 통과하므로(설계된 동작), 완전 해소는 아니다.

### G-6. 멱등·재생 창이 휘발성이다

`DedupeStore` 와 재생 창 모두 프로세스 메모리다. 재시작하면 dedupe 가 비어 **재소비 시 중복 발송**되고,
재생 창이 사라져 `Last-Event-ID` 재개가 조용히 "라이브만"으로 떨어진다. 레플리카가 2개 이상이면 클라이언트가
붙은 레플리카가 가진 것만 재개된다. `polyglot-services.md` 가 알려진 한계로 명시하고 있으며, 하류 Java
컨슈머의 `processed_events` 멱등 덕분에 **회계 영향은 없다** — 손상은 수신자 경험에 국한된다.

### G-7. `../../sse.md` 정본은 해소됐다 (2026-08-13 추가)

`JwtSubscriberIdentityResolver:118`·`InMemoryNotificationStream:33` 두 곳의 KDoc 과 루트 `CLAUDE.md` 가
`../../sse.md` 를 SSE 정본으로 참조한다. 역산 착수 시점에는 이 파일이 없어 dangling 참조였으나, **작업 중
병행 세션이 `../../sse.md` 를 추가해 해소됐다**(토큰 URL 트레이드오프, 재생 정책, 하트비트,
멀티 레플리카 한계까지 수록). 다만 해당 문서도 아래 G-1~G-3(배선·시크릿)은 다루지 않으므로 이 PRD 가
그 부분의 유일한 기록이다.

### G-8. Email 채널의 성공 경로가 미검증이다

`EmailChannel` 주석이 명시하듯 활성 경로는 라이브 SMTP 없이 테스트되지 않는다. 단위 테스트는 비활성
경로와 팬아웃 계약만 덮는다. Slack 도 동일하게 실 웹훅 검증은 없다.

### G-9. 부분 실패 채널은 경고 로그가 전부다

한 채널만 실패하면(예: Slack 만 죽음) DLT 로 가지 않는 것이 의도(BR-3)인데, 그 실패에 대한 후속 조치가
warn 로그 외에 없다. 실패 채널만 골라 재전송하는 경로도, 실패율 메트릭도 없다(카운터는 DLT·retry 2종뿐).

## 13. 추적 항목

| #   | 항목                                                            | 상태                   |
| --- | --------------------------------------------------------------- | ---------------------- |
| T-1 | `app.security.jwt.secret` 를 `application.yml` 에 환경변수로 선언 | **해소** (G-1)         |
| T-2 | compose 서비스 정의 + `NOTIFICATION_SERVICE_URI`                | **해소** (G-2)         |
| T-3 | nginx `/api/notifications/` 무버퍼 location                      | **해소** (G-3)         |
| T-4 | 실배포 `APP_KAFKA_ENABLED=true` 주입 경로                        | compose 만 해소 (G-4)  |
| T-5 | `/notifications/send`·`/demo` 인가 게이트                        | 없음 (G-5)             |
| T-6 | 내구 dedupe·재생 저장소(Redis 등)                                | 인메모리 (G-6)         |
| T-7 | `../../sse.md` 작성 — 코드가 참조하는 정본                        | **해소** (G-7)         |
| T-8 | 채널별 성공/실패 메트릭                                          | 없음 (G-9)             |
