# ADR 0006 — Toss PG 호출 회복탄력성 (Resilience4j)

- 상태: Accepted
- 일자: 2026-02-10

## 컨텍스트

결제 승인은 외부 PG(Toss Payments)의 동기 HTTP 호출에 의존한다. order-service 의
`TossPaymentService.callTossConfirmApi` 가 `RestTemplate` 으로
`https://api.tosspayments.com/v1/payments/confirm` 를 호출해 paymentKey 를 검증한 뒤에야
결제 READY → AUTHORIZED → CAPTURED 로 진행한다.

외부 PG 호출에는 두 가지 실패 양상이 섞여 있다:

- **인프라성 실패** — PG 네트워크 지연·타임아웃·5xx. 일시적이며 재시도로 회복 가능하지만,
  방치하면 호출 스레드가 read timeout 까지 점유되어 톰캣 워커 풀이 고갈되고, PG 장애가
  곧 결제 서비스 전체 장애로 번진다.
- **비즈니스성 실패** — PG 가 4xx 로 거절(만료된 paymentKey, 금액 불일치, 한도초과 등).
  재시도해도 결과가 같고, 이를 "장애"로 집계하면 정상 거절이 서킷을 열어 멀쩡한 PG 를
  차단해버린다.

두 양상을 구분하지 않으면 회복탄력성 장치가 오히려 가용성을 떨어뜨린다.

## 결정

Toss PG 호출에 **Resilience4j Circuit Breaker + Retry** 를 적용하고, **4xx 비즈니스 오류는
서킷·재시도 판정에서 모두 제외**한다.

### 1. 적용 지점

`TossPaymentService.callTossConfirmApi` 에 어노테이션을 선언한다. 인스턴스명은 `tossPg`.

```java
@CircuitBreaker(name = "tossPg", fallbackMethod = "tossFallback")
@Retry(name = "tossPg")
public void callTossConfirmApi(String paymentKey, String tossOrderId, Long amount) { ... }
```

### 2. 4xx 제외 (`ignoreExceptions`)

`application.yml` 의 `resilience4j.circuitbreaker`·`resilience4j.retry` 양쪽에서
`org.springframework.web.client.HttpClientErrorException`(4xx)을 `ignoreExceptions` 로 지정한다.
재시도 대상(`retryExceptions`)은 `ResourceAccessException`·`HttpServerErrorException`·`IOException`
으로 한정한다. 그 결과:

- 4xx → 재시도 안 함, 서킷 카운트에 반영 안 함, `IllegalStateException` 으로 즉시 전파.
- 5xx·타임아웃·I/O → exponential backoff 재시도(`multiplier=2`), 실패는 서킷 통계에 집계.

### 3. 타임아웃과 폴백

Boot 4 에서 `RestTemplateBuilder` 가 빠져 `SimpleClientHttpRequestFactory` 로 connect 3s /
read 5s 타임아웃을 직접 설정해 스레드 점유 상한을 둔다. 서킷 OPEN 또는 재시도 소진 시
`tossFallback` 이 호출되며, 4xx 비즈니스 오류는 그대로 전파하고 그 외 장애는
"일시 장애로 결제 확인 불가" 메시지로 사용자에게 명확히 알린다(운영은 알림 연계).

## 결과

### 좋아지는 점
- PG 일시 장애가 결제 서비스 전체 장애로 번지지 않음 — 스레드 고갈·연쇄 장애 차단
- 정상 거절(4xx)이 서킷을 열지 않아 가용성 오판이 없음
- 재시도가 멱등하지 않은 부작용을 만들지 않도록 4xx·승인 부작용 분리

### 트레이드오프 / 리스크
- 재시도로 외부 호출 지연이 누적될 수 있어 read timeout·재시도 횟수 튜닝 필요
- 폴백 경로에서의 사용자 메시지·운영 알림 일관성을 별도로 관리해야 함
- 어노테이션 기반 AOP 라 self-invocation(같은 빈 내부 호출) 시 미적용 — 진입 메서드 분리 준수

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **Resilience4j CB + Retry, 4xx ignore (본 결정)** | ✓ | 인프라/비즈니스 실패 분리, 선언적, Boot 통합 성숙 |
| 단순 try-catch 재시도 루프 | ✗ | 서킷·통계·half-open 전이 부재 — 장애 전파 차단 불가 |
| 모든 예외를 서킷에 집계 | ✗ | 정상 4xx 거절이 멀쩡한 PG 를 차단 |
| Hystrix | ✗ | 유지보수 종료, Resilience4j 가 표준 |

## 개정 이력

### 2026-08-05 — 적용 지점 변경: `TossConfirmApiClient` 로 분리

본 ADR 의 "트레이드오프 / 리스크" 마지막 줄이 경고한 사고가 실제로 발생해 있었다 —
`TossPaymentService.confirmTossPayment()` 가 같은 클래스의 `callTossConfirmApi()` 를
자기호출하고 있어, **선언된 `@CircuitBreaker`·`@Retry` 가 한 번도 동작하지 않았다**.
스프링 AOP 는 프록시 기반이므로 `this.method()` 호출은 어드바이스를 거치지 않는다.

기존 `TossPaymentResilienceTest` 는 Resilience4j 를 프로그래매틱 API 로 검증해
"설정값이 옳음"만 증명했을 뿐, "그 설정이 호출 경로에 걸려 있음"은 증명하지 않아 결함이 드러나지 않았다.

- **적용 지점**: `TossPaymentService.callTossConfirmApi` → **`TossConfirmApiClient.confirm`**
  (별도 스프링 빈). 호출이 외부 호출이 되어 프록시를 통과한다.
- 인스턴스명(`tossPg`)·4xx 제외 정책·타임아웃·폴백 동작은 **변경 없음** — `application.yml` 수정 불필요.
- **회귀 차단**: `../../scripts/harness/test/aop-proxy-gate.test.mjs` 가 리포 전수로 프록시 의존 애노테이션의
  자기호출을 0건으로 강제한다(CI 필수). 경위·근거는 `../inflearn/spring.md`(로컬 전용, 저장소 미포함).

## 참조

- [0010 — 다중 PG 라우팅 + Bulkhead](0010-multi-pg-routing-and-bulkhead.md)
- `../inflearn/spring.md`(로컬 전용, 저장소 미포함) — 프록시 내부 호출 결함의 발견·수정 기록
