# Runbook — Toss PG 장애

**연결 알림:** `TossPgCircuitOpen` (critical)
**담당:** 백엔드 온콜 → 결제팀

## 증상

`resilience4j_circuitbreaker_state{name="tossPg", state="open"} > 0` — Toss 서킷 OPEN.
결제 API `/api/payments/confirm` 호출이 503 혹은 "Toss PG 일시 장애" 로 실패.

## 1. 상태 확인

```bash
# 서킷 상태
curl https://app.lemuel.internal/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:tossPg

# Toss 상태 페이지
open https://status.tosspayments.com/
```

- Toss 측 공식 장애 고지가 있으면 → 2번으로 (단순 대기).
- Toss 측 정상인데 우리 서킷만 OPEN → 3번으로 (네트워크/인증 체크).

## 2. Toss 측 장애 — 대응

- 공지 배너 (`/maintenance`) 띄우기.
- `#alerts-critical` 에 ETA 공유.
- 서킷은 30초 간격으로 HALF-OPEN 전환 시도 → 자동 복구.
- 서킷 OPEN 지속 시간 모니터링, Toss 복구 공지 후 수동 CLOSE 가능:
  ```bash
  curl -X POST https://app.lemuel.internal/actuator/circuitbreakerevents \
    -H "Content-Type: application/json" \
    -d '{"name":"tossPg","transition":"CLOSE"}'
  ```

## 3. 우리 측 문제 — 체크리스트

- Toss secret-key 회전됐는가? `TOSS_SECRET_KEY` 환경변수 확인.
- 방화벽/아웃바운드 IP 차단? `curl -v https://api.tosspayments.com/v1/payments` 로 핸드셰이크.
- DNS 문제? `nslookup api.tosspayments.com`.
- 샌드박스 URL 이 prod 로 잘못 세팅되지 않았는지 `toss.api-url` 재확인.

## 4. 장기 장애 대응

- 30분 이상 장애 지속 → 결제 수단 전환 정책 (별도 PG 추가 ADR 필요).
- 주문 큐 임시 중단 — 고객이 결제 실패로 이탈하는 것보다 "결제 일시 불가" 안내가 나음.

## 5. 사후

- Toss 장애 후 재시도 건수 급증 확인 — `rate(refund_requests_total[5m])` 스파이크.
- `/incidents/YYYY-MM-DD-toss-outage.md` 기록.
