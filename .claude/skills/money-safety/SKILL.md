---
name: money-safety
description: 금액 연산 안전 규칙 — BigDecimal 강제, 라운딩 정책, 직렬화, 통화 단위. 금액을 다루는 코드를 작성하거나 리뷰할 때 로드.
---

# Money Safety

## 절대 규칙

1. **금액 타입은 BigDecimal.** `float`/`double`/`Float`/`Double` 로 금액(amount, fee, commission,
   holdback, payout, balance, total, price)을 선언·연산·파싱하는 코드는 작성 금지, 발견 시 지적.
   - 나쁜 예: `double fee = amount * 0.035;`
   - 좋은 예: `BigDecimal fee = amount.multiply(rate).setScale(0, RoundingMode.HALF_UP);`
2. **라운딩 명시.** `divide`/`setScale` 에 `RoundingMode` 를 항상 지정 (코드베이스 표준: `HALF_UP`).
   미지정 `divide` 는 `ArithmeticException` 폭탄이다.
3. **비교는 `compareTo`.** `equals` 는 scale 이 다르면 false (`0` vs `0.00`) — 금액 비교에 쓰지 마라.
4. **생성은 문자열/정수로.** `new BigDecimal(0.035)` 금지 → `new BigDecimal("0.0350")`.
   (실제 코드: `SellerTier("0.0350", ...)` 가 이 패턴이다.)
5. **KRW 는 원 단위 정수 스케일.** 소수점 금액이 생기면 라운딩 시점을 명시하고 그 근거를 주석으로 남겨라.

## 계산 순서 규칙 (합계 오차 방지)

- 항목별 계산 → 합산 순서를 지켜라. "총액에 요율 곱하기"와 "건별 요율 곱해서 합산"은
  라운딩 때문에 결과가 다르다. 이 코드베이스는 **건별 계산 후 합산**이 표준.
- 수수료 차감 후 net 에 홀드백율을 적용한다 (gross 에 적용 금지) — settlement-domain-rules 참조.

## 직렬화/경계

- API 응답에서 금액은 십진 문자열 또는 BigDecimal 직렬화 그대로 — JS `Number` 변환 제안 금지.
- Kafka 이벤트 페이로드의 금액 필드도 문자열 십진수로 유지한다.
- DB 컬럼은 `NUMERIC(19,2)` 계열 — `REAL`/`DOUBLE PRECISION` 마이그레이션은 반려.

## 테스트 템플릿

금액 로직 구현 시 최소 케이스:

```java
// 1) 대표값: 1,000,000원 NORMAL → fee 35,000 / net 965,000 / holdback 289,500 / 즉시 675,500
// 2) 라운딩 경계: 요율 곱이 .5 로 떨어지는 금액 (HALF_UP 검증)
// 3) 0원, 1원, 최대금액
// 4) 환불(음수 조정) 라운딩 대칭성 — 정산 fee + 역정산 fee 반환 합이 0인지
```

기대값은 손계산 대신 MCP `settlement_simulate` 로 재확인하라.
