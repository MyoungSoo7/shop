package github.lms.lemuel.payment.domain;

/**
 * 지원되는 결제 게이트웨이 (PG) 식별자.
 *
 * <p>이커머스에서 단일 PG 만 사용하면 PG 장애 = 매출 정지가 된다. 실제 결제팀은
 * 2~4 개 PG 를 동시 연동하고 결제 수단·수수료·건강도에 따라 동적으로 라우팅한다.
 *
 * <p>각 항목의 prefix 는 {@code pgTransactionId} 의 prefix 로 인코딩되어
 * capture / refund 시 동일 PG 로 라우팅하는 데 사용된다 — 예: {@code "TOSS:abc-123"}.
 */
public enum PaymentGateway {
    TOSS("TOSS"),
    KCP("KCP"),
    NICE("NICE"),
    INICIS("INICIS"),
    /** 테스트·로컬용 mock 어댑터 — 실제 외부 호출 없음 */
    MOCK("MOCK");

    public static final String TRANSACTION_ID_DELIMITER = ":";

    private final String prefix;

    PaymentGateway(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /**
     * pgTransactionId 의 prefix 로부터 어떤 PG 가 처리한 거래인지 식별한다.
     * prefix 가 인식되지 않으면 {@link #MOCK} 으로 폴백 — 과거 prefix 없는 데이터 호환.
     */
    public static PaymentGateway fromTransactionId(String pgTransactionId) {
        if (pgTransactionId == null || pgTransactionId.isBlank()) {
            return MOCK;
        }
        int delim = pgTransactionId.indexOf(TRANSACTION_ID_DELIMITER);
        if (delim <= 0) {
            return MOCK;
        }
        String prefix = pgTransactionId.substring(0, delim);
        for (PaymentGateway pg : values()) {
            if (pg.prefix.equalsIgnoreCase(prefix)) {
                return pg;
            }
        }
        return MOCK;
    }
}
