# 결제 API 안전 원칙 5가지 — 원칙과 우리 코드의 현재 위치

> **감사 기준일**: 2026-08-22 · **대상**: order-service(payment) · card-service(VAN) · gateway/nginx
> (2026-08-25 갱신: 감사 대상이던 `payment-webhook-service` 는 제거됐다 — 아래 웹훅 행 참조)
>
> 이 문서는 결제 API 를 다룰 때 지켜야 할 5원칙을 적고, 각 원칙이 **이 저장소 어디에서 어떻게
> 지켜지는지(또는 지켜지지 않는지)** 를 파일 단위 근거와 함께 남긴다. 원칙만 적힌 문서는 다음
> 사람에게 아무것도 알려주지 못한다 — 판정과 근거가 같이 있어야 한다.

---

## 원칙 요약

| # | 원칙 | 한 줄 요지 |
|---|---|---|
| 1 | **가맹점 식별 API Key** | 모든 요청 헤더에 가맹점을 식별하는 API Key 를 포함시킨다 |
| 2 | **서버 간 통신(S2S) 우선** | 브라우저·앱이 아니라 서버끼리 직접 통신한다. 클라이언트를 거치는 구간이 늘수록 위·변조 위험이 커진다 |
| 3 | **TLS 1.2 이상** | 전송 구간 암호화는 협상의 여지가 없는 최소 기준이다 |
| 4 | **멱등성 키(Idempotency Key)** | 네트워크 재시도로 같은 결제가 중복 승인되는 것을 막는다. 특히 POST 는 필수 고려 |
| 5 | **IP 화이트리스트** | 가맹점 서버 ↔ PG 서버 통신은 제한된 IP 대역으로만 열어둔다 |

---

## 1. 가맹점 식별 API Key — △ 방향이 반쪽

**우리가 "가맹점"일 때는 지켜진다.** Toss 를 호출하는 모든 요청이 시크릿 키를 Basic 자격으로 싣는다.

- `order-service/.../payment/application/TossConfirmApiClient.java:79` — `Authorization: Basic base64(secretKey:)`
- `order-service/.../payment/application/TossCancelApiClient.java:87` — 동일
- 키는 `toss.secret-key: ${TOSS_SECRET_KEY}`(`application.yml:365`) 로 환경변수 주입. 하드코딩 없음.

**우리가 "PG 역할"을 하는 진입점에는 호출자별 키가 없다.**

| 진입점 | 현재 방어 | 부족한 점 |
|---|---|---|
| `/van/**` (card-service 승인·매입·취소·환불) | 공유 시크릿 1개 `X-Internal-Api-Key` (`InternalApiKeyFilter`) | 호출자 식별 불가 — 실제 VAN 온보딩 시 별도 시크릿 분리 필요(필터 주석에 명시) |
| ~~`/webhooks/toss`~~ | — | **표면 자체가 사라졌다(2026-08-25)** — `payment-webhook-service` 제거. 웹훅 수신을 다시 붙일 때는 아래 미완 항목(실제 Toss 서명 규격·IP allowlist)을 전제로 설계한다 |
| `/internal/**` | 공유 시크릿 1개 | 상동 |

- 저장소 전체 마이그레이션에 **`api_key` 테이블 0건** — 키 발급·회전·폐기 절차가 존재하지 않는다.
- **fail-open 기본값**: `app.security.internal-key-required` 기본 `false` 라 키 미설정이면 경고만 찍고 통과한다
  (`InternalApiKeyFilter.java:63-77`). 운영 배포는 `true` + `INTERNAL_API_KEY` 주입이 필수다.

## 2. 서버 간 통신(S2S) 우선 — ○ (2026-08-22 보완 완료)

지켜지는 부분:

- Toss 승인은 **서버가** `api.tosspayments.com` 을 직접 호출한다(`TossConfirmApiClient`). 브라우저가 PG 를 부르지 않는다.
- `TossLivePgAdapter.authorize()` 는 **서버 개시 승인을 예외로 차단**한다 — mock 어댑터가 운영에서
  랜덤 승인을 뱉던 경로를 막았다. "조용한 가짜 승인보다 시끄러운 실패".
- `/van/**`·`/internal/**` 은 gateway 라우트 predicate 목록에 없어 외부에 노출되지 않는다
  (`gateway-service/src/main/resources/application.yml`).

**과거의 구멍 (수정 완료)** — 결제 금액과 주문 소유자를 서버가 대조하지 않았다.

브라우저가 `POST /payments/toss/confirm` 본문에 `amount` 와 `dbOrderId` 를 실어 보내고, 서버는 그
값을 **DB 주문과 한 번도 비교하지 않고** Toss 로 넘겼다. Toss 는 "결제창 개설 금액 == confirm 금액"
만 본다. 결제창 금액도 브라우저가 정하므로, 두 값을 서버가 이어주지 않으면:

- 10,000원짜리 주문에 1,000원짜리 결제창을 열어 **정상 승인**을 받고, 주문은 전액 결제로 기록된다.
  결제행 금액은 `CreatePaymentUseCase` 가 DB 주문에서 가져오므로 불일치가 조용히 성립한다.
- 남의 `dbOrderId` 만 알면 그 주문을 결제 완료 상태로 만들 수 있다(IDOR).

**조치** — `TossPaymentService` 가 PG 호출 **전에** 두 가지를 대조한다:

```
confirm 요청 → [멱등 replay 확인] → [소유권 대조] → [금액 대조] → Toss confirm → 결제 생성/승인/캡처
                                    ↑ 여기서 막아야 돈이 움직이기 전이다
```

- 소유권: 요청 본문이 아니라 **JWT 주체**(`PaymentController.callerUserIdOrAdminBypass()`)에서 파생한
  `callerUserId` 를 주문 소유자와 대조 → 불일치 시 `PaymentOwnershipException`(403).
  주문 소유자를 알 수 없으면 **통과가 아니라 거부**한다(fail-closed).
- 금액: `order.amount.compareTo(요청금액) != 0` → `PaymentAmountMismatchException`(409).
  `equals` 가 아니라 `compareTo` 인 이유는 `10000.00` 과 `10000` 을 같게 보기 위해서다.
  **과납도 거절**한다 — 주문 금액과 다른 입금은 정산·대사에서 출처 불명 금액이 되어 결국 사람이 푼다.
- 장바구니 일괄(`/toss/cart/confirm`)은 **주문 금액 합계 == 총 승인액**, 그리고 전 건 소유권을 본다.
  한 건이라도 어긋나면 전체 거절 — 부분 승인은 "돈은 들어왔는데 어느 주문 것인지 모르는" 상태를 만든다.
- ADMIN/MANAGER 는 소유권 대조를 건너뛴다(운영 지원 대행). **금액 대조는 운영자 경로에도 적용된다.**

남은 관찰: `PATCH /payments/{id}/authorize|capture` 는 로그인만 하면 호출된다
(`SecurityConfig.java:293` 이 의도적으로 열어둠). 상태머신이 비정상 전이를 막아 실피해로 이어지지는
않지만, S2S 원칙상 브라우저가 몰 경로는 아니다.

## 3. TLS 1.2 이상 — ○ 아웃바운드 / ✗ 인바운드 근거 없음

- **아웃바운드**: 전부 `https://` 고정(`application.yml:366,369`). JDK 25 기본 SSLContext 는 TLS 1.2/1.3
  만 협상하므로 최소기준은 충족한다. 다만 **명시적으로 강제하는 코드는 없다** —
  `SimpleClientHttpRequestFactory` 기본값에 기대고 있다(`TossCancelApiClient.java:57-61`).
- **인바운드**: 근거가 저장소 안에 없다. `nginx.conf:20` 은 `listen 80;` 뿐이고 HSTS 는 주석 처리(:36),
  `server.ssl.*` 설정 0건, `k8s/` 아래에 Ingress·TLS 리소스가 없다(`buildkit/` 뿐). 전송 암호화가
  외부 LB 에 위임돼 있고 그 위임이 코드로 확인되지 않는 상태다.

## 4. 멱등성 키 — ◎ (2026-08-22 승인 경로 보완 완료)

원래도 5원칙 중 가장 촘촘했다.

| 경로 | 장치 |
|---|---|
| 주문 생성 | `Idempotency-Key` 헤더 + 분산 락 + DB UNIQUE (`IdempotentMultiItemOrderService`, `order_idempotency`) |
| 부분 환불 | 헤더 **필수**, `idx_refunds_payment_idempotency` UNIQUE (`V4`) |
| Toss 취소(아웃바운드) | `Idempotency-Key` 전달 + **키 없으면 호출 자체를 거부** (`TossCancelApiClient:78-89`) |
| PG 거래키 | `uq_payments_pg_txn` 부분 UNIQUE — 웹훅 이중발화 차단 (`V20260718120000`) |
| 주문당 결제 | `idx_payments_order_id_unique` (`V3`) |
| VAN 승인 | `networkRequestId` 자연키 멱등 (`AuthorizationVanAdapter`) |
| 웹훅 | `eventType:paymentKey` dedupe (`handler.go`) — ⚠️ `MemoryStore` 라 재시작·다중 인스턴스에서 소실 |

**과거의 구멍 (수정 완료)** — 정작 **승인 POST** 에 멱등 키가 없었다. `POST /payments/toss/confirm`,
`POST /payments/toss/cart/confirm` 은 재시도 시 방어선이 하류 UNIQUE 뿐이라 **이중 결제행은 막히되
사용자에게는 500 이 나갔다**. 원칙이 "POST 는 필수 고려" 라고 짚은 자리가 정확히 비어 있었다.

**조치** — `payment_idempotency`(키 → payment_id) 테이블로 최초 결과를 **replay** 한다
(`V20260822120000__payment_idempotency.sql`, `PaymentIdempotencyPort`).

- 키는 클라이언트의 `Idempotency-Key` 헤더, **없으면 결제창이 발급한 `paymentKey`** 를 쓴다.
  paymentKey 는 그 승인 시도를 유일하게 가리키므로, **헤더를 보내지 않는 기존 클라이언트도 보호된다.**
- 재요청은 PG 를 다시 부르지 않고 최초 결제를 그대로 돌려준다 → 방어가 오류가 아니라 정상 응답이 된다.
- 매핑 INSERT 는 네이티브 쿼리다(`save()` 는 merge=UPDATE 라 UNIQUE 위반이 안 난다). 동시 중복 요청이
  둘 다 조회를 통과해도 두 번째 INSERT 가 제약 위반으로 트랜잭션을 롤백시켜 최종 1건이 남는다.
- 매핑이 있는데 결제가 없으면 조용히 재승인하지 않고 `PaymentNotFoundException` 으로 드러낸다.

**남은 한계(의도적)**: 완전 동시에 도착한 동일 키 두 건은 둘 다 조회를 통과해 Toss 를 호출할 수 있다.
이 경우 Toss 가 "이미 처리된 결제" 4xx 를 돌려주고 우리 트랜잭션은 롤백된다 — **이중 청구는 없고**
두 번째 요청이 4xx 를 받는다. 분산 락까지 씌우려면 order 생성 경로처럼 `DistributedLockPort` 를
결제 경계에도 도입해야 하는데, 현재 위험 대비 비용이 맞지 않아 두지 않았다.

일괄 승인은 키 1개에 결제 N건이라 매핑에 **대표 1건만** 보관한다. replay 는 그 1건을 돌려준다 —
재시도의 목적은 "이중 승인 방지"이지 응답 재구성이 아니기 때문이다.

## 5. IP 화이트리스트 — ✗ 사실상 없음

저장소 전체에서 `allow`/`deny` 는 **`nginx.conf:105-108` 의 `/actuator/` 한 곳뿐**이다
(`10.0.0.0/8`, `172.16.0.0/12`, `127.0.0.1`).

결제 인바운드 경계에는 IP 제한이 0건이다:

- `/van/**` — 방어는 gateway 미라우팅 + 공유 시크릿뿐
- ~~`/webhooks/toss`~~ — 서비스가 제거돼 표면이 없다. 미배선(compose 부재)이 결국 제거 근거가 됐다
- `/internal/**` — 공유 시크릿뿐. `SecurityConfig` 주석도 "운영선 NetworkPolicy/mTLS 추가 권장" 으로 미완을 인정
- `frontend/nginx.conf`, `frontend/nginx.compose.conf` 에도 allow/deny 없음

nginx `limit_req`(`nginx.conf:12-14`)와 Bucket4j `RateLimitFilter` 는 IP **기반**이지만 화이트리스트가 아니다.
아웃바운드(우리 → Toss)는 우리가 등록당하는 쪽이라 코드 이슈는 아니나, 고정 egress IP 를 보장하는
배포 매니페스트가 없다.

---

## 남은 과제 (우선순위)

1. **웹훅 dedupe 영속화** — `MemoryStore` 는 재시작 한 번에 무력화된다. Redis(SET NX + TTL) 또는 DB UNIQUE 로.
2. **IP 화이트리스트** — `/actuator/` 패턴을 `/van/`·`/webhooks/` 로 확장하거나 k8s NetworkPolicy 로.
3. **호출자별 API Key** — 실제 VAN·파트너 온보딩 전까지는 공유 시크릿으로 버틸 수 있으나,
   키 테이블 없이는 회전·폐기가 불가능하다.
4. **인바운드 TLS** — 운영 배포 형상이 저장소 밖이라면 최소한 종단 지점을 문서에 명시한다.
5. **Toss 실제 웹훅 서명 규격** — 현재는 일반형 HMAC(raw body). 운영 전 교체 필요.

## 관련 문서

- 커머스 도메인 규칙(상태머신·환불 3단·재고) → `.claude/skills/order-commerce-rules`
- 멱등·이벤트 3단 방어 → `.claude/skills/idempotency-and-events`
- 금액 안전(BigDecimal·라운딩) → `.claude/skills/money-safety`
- 보안 설정 요약 → [`CLAUDE.md`](../CLAUDE.md) §보안
