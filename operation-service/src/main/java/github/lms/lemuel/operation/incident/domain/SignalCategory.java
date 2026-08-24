package github.lms.lemuel.operation.incident.domain;

/**
 * 운영 관제 신호 카테고리 — 운영자가 보는 9개 관심사 + 운영용 2종.
 *
 * <p>Phase 1 은 Alertmanager 알람의 {@code labels.component} 를
 * {@code app.ops.category-mapping}(yml 외부화) 으로 매핑해 채운다.
 * Phase 2 의 실패 이벤트(주문/결제/재고/배송/정산)가 나머지 카테고리를 채운다.
 */
public enum SignalCategory {
    /** 주문 실패 (Phase 2: lemuel.ops.order.failed) */
    ORDER_FAILURE,
    /** 결제/환불 실패 (Phase 1: component=refund, Phase 2: lemuel.ops.payment.failed) */
    PAYMENT_FAILURE,
    /** 재고 부족 (Phase 2: lemuel.ops.stock.depleted) */
    STOCK_SHORTAGE,
    /** 배송 지연 (Phase 2: lemuel.ops.shipping.delayed) */
    SHIPPING_DELAY,
    /** 정산 실패 (component=settlement-batch/settlement-adjustment/cashflow-report) */
    SETTLEMENT_FAILURE,
    /** Kafka 적체·이벤트 파이프라인 (component=kafka-consumer/outbox/settlement-projection) */
    KAFKA_BACKLOG,
    /** Redis 장애 (Phase 2: redis-exporter) */
    REDIS_FAILURE,
    /** DB 데드락/커넥션 (component=database) */
    DB_DEADLOCK,
    /** API 지연/에러율 (component=http) */
    API_TIMEOUT,
    /** 기타 인프라 (component=jvm/application) */
    INFRA_ETC,
    /** 매핑 실패 폴백 — 발생 시 매핑 누락이므로 경고 로그 대상 */
    UNKNOWN
}
