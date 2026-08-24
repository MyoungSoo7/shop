package github.lms.lemuel.common.opssignal;

import java.time.Instant;
import java.util.Map;

/**
 * 운영 관제 실패 신호 공통 envelope.
 *
 * <p>attributes 에는 PII 를 싣지 않는다 — ID 참조와 사유/금액 같은 비식별 메타만 (기존 감사 마스킹 정책과 일관).
 *
 * @param category   실패 카테고리(토픽 라우팅)
 * @param service    발원 서비스명 (spring.application.name)
 * @param entityType 대상 엔티티 종류 (예: "payout", "refund", "variant", "shipment")
 * @param entityId   대상 식별자
 * @param severity   심각도 (기본 ERROR)
 * @param occurredAt 발생 시각
 * @param attributes 비식별 부가 메타 (reason, amount, sellerId 등)
 */
public record OpsSignal(
        OpsSignalCategory category,
        String service,
        String entityType,
        String entityId,
        String severity,
        Instant occurredAt,
        Map<String, Object> attributes
) {
    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARNING = "WARNING";
}
