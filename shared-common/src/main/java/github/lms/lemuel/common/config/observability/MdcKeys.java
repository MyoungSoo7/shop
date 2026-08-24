package github.lms.lemuel.common.config.observability;

/**
 * 구조화 로그용 MDC 키 정의.
 *
 * <p>모든 서비스는 비즈니스 컨텍스트를 MDC 에 넣어야 한다. 그래야 JSON 로그에서
 * 결제→정산→환불 체인을 traceId 없이도 식별자(paymentId, settlementId 등) 로 연결 가능.
 */
public final class MdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String ORDER_ID = "orderId";
    public static final String PAYMENT_ID = "paymentId";
    public static final String SETTLEMENT_ID = "settlementId";
    public static final String REFUND_ID = "refundId";

    /** {@code @Transactional} 메서드 경계마다 부착되는 트랜잭션 상관 키. */
    public static final String TX_ID = "txId";

    private MdcKeys() {}
}
