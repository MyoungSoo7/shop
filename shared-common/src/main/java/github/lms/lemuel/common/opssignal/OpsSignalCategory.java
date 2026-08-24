package github.lms.lemuel.common.opssignal;

/**
 * 운영 관제 실패 신호 카테고리 — operation-service Phase 2b.
 *
 * <p>각 카테고리는 전용 Kafka 토픽으로 발행되고, operation-service 가 구독해 신호 버킷의
 * {@code count_signal}(분자=실패)을 올린다. 성공 이벤트(분모)는 Phase 2a 에서 이미 흐른다.
 *
 * <p>이 신호들은 관측/통계 목적이므로 <b>best-effort</b> 로 발행된다 — 발행 실패가 비즈니스
 * 트랜잭션을 절대 막지 않는다(실패는 흔히 트랜잭션 롤백을 동반하므로 Outbox 대신 out-of-band).
 */
public enum OpsSignalCategory {

    /** 예약 카테고리 — 프로덕션 emit 지점 미배선(operation 구독만 존재). 주문 실패 신호가 필요해지는 시점에 배선한다. */
    ORDER_FAILED("lemuel.ops.order.failed"),
    PAYMENT_FAILED("lemuel.ops.payment.failed"),
    STOCK_DEPLETED("lemuel.ops.stock.depleted"),
    SHIPPING_DELAYED("lemuel.ops.shipping.delayed"),
    /** 배송 후 환불로 보류된 재고가 회수되지 않고 임계를 넘김 — 팔 수 있는 물건이 묶인 상태. */
    STOCK_RECLAIM_DELAYED("lemuel.ops.stock.reclaim_delayed"),
    SETTLEMENT_FAILED("lemuel.ops.settlement.failed");

    private final String topic;

    OpsSignalCategory(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }
}
