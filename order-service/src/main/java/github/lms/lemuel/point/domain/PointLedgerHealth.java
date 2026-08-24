package github.lms.lemuel.point.domain;

import java.math.BigDecimal;

/**
 * 포인트 원장 3자 대조 — "이 계정의 잔액이 설명되는가"에 답하는 값.
 *
 * <p>같은 잔액을 서로 다른 세 경로가 말한다:
 *
 * <ol>
 *   <li><b>계정 요약</b> {@code point_accounts.total} — 고객이 가진 포인트의 총량
 *   <li><b>로트 상세</b> Σ{@code point_lots.remaining_amount} (ACTIVE) — 유효기간·출처별 실체
 *   <li><b>원장 누계</b> Σ(GRANT+RESTORE) − Σ(USE+EXPIRE+REVOKE) — append-only 기록의 합
 * </ol>
 *
 * <p>셋은 갱신 경로가 달라서, 어긋났다면 <b>잔고만 움직이고 기록이 빠진</b> 트랜잭션이 있었다는 뜻이다.
 * 정산 시산표가 차·대를 맞춰 보는 것과 같은 역할을 포인트에서 한다 — 운영자가 "지금 장부가
 * 성립하는가"를 눈으로 확인할 수 있어야 수기 지급·소멸 같은 조작을 믿고 누를 수 있다.
 *
 * <p><b>왜 {@code available} 이 아니라 {@code total} 인가</b>: 입금 대기 결제가 포인트를 선점하면
 * ({@link PointHold}) 그 몫은 {@code available} 에서 {@code locked} 로 옮겨간다. 선점은 <b>로트를
 * 건드리지 않으므로</b> Σ remaining 은 그대로다 — 그래서 {@code available} 로 비교하면 선점이
 * 걸린 계정마다 선점액만큼 드리프트가 잡힌다(정상인데 장부가 깨진 것처럼 보인다).
 * {@code total = available + locked} 이 로트·원장과 맞물리는 축이다.
 *
 * <p>드리프트의 <b>부호는 계정 요약 기준</b>이다: 양수면 요약이 더 크고(상세가 모자람),
 * 음수면 상세가 더 크다. 어느 쪽이 진실인지는 이 값이 판단하지 않는다 — 조사 대상만 지목한다.
 */
public record PointLedgerHealth(BigDecimal accountTotal,
                                BigDecimal activeLotRemaining,
                                BigDecimal entryNet) {

    /**
     * 집계 결과로 대조값을 만든다.
     *
     * <p>null 을 0 으로 착지시키는 이유: 로트도 엔트리도 없는 신규 계정에서 {@code SUM()} 은
     * 0 이 아니라 NULL 을 돌려준다. 여기서 막지 않으면 "거래가 없는 계정"을 볼 때마다 콘솔이 터진다.
     */
    public static PointLedgerHealth of(BigDecimal accountTotal,
                                       BigDecimal activeLotRemaining,
                                       BigDecimal entryNet) {
        return new PointLedgerHealth(nz(accountTotal), nz(activeLotRemaining), nz(entryNet));
    }

    /** 계정 요약 − 로트 상세. 0 이 아니면 로트와 잔고가 따로 논 것이다. */
    public BigDecimal lotDrift() {
        return accountTotal.subtract(activeLotRemaining);
    }

    /** 계정 요약 − 원장 누계. 0 이 아니면 엔트리를 남기지 않은 잔고 변경이 있었다. */
    public BigDecimal entryDrift() {
        return accountTotal.subtract(entryNet);
    }

    /**
     * 두 드리프트가 모두 0 인가.
     *
     * <p>{@code signum()} 으로 판정한다 — {@code equals} 는 스케일까지 비교해
     * {@code 0.00} 과 {@code 0} 을 다르다고 하고, JDBC 는 NUMERIC(19,2) 를 스케일 2 로 돌려준다.
     */
    public boolean balanced() {
        return lotDrift().signum() == 0 && entryDrift().signum() == 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
