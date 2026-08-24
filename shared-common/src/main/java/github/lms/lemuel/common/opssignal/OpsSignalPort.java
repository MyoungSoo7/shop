package github.lms.lemuel.common.opssignal;

import java.util.Map;

/**
 * 운영 관제 실패 신호 발행 포트 — 전 서비스가 실패 지점에서 호출한다.
 *
 * <p>계약: <b>절대 예외를 던지지 않는다</b>. 발행 실패(브로커 다운·직렬화 오류 등)는 내부에서
 * 삼키고 로그만 남긴다 — 관측 신호가 결제/정산 같은 비즈니스 경로를 깨선 안 된다.
 * app.kafka.enabled=false 환경(단위 테스트 등)에서는 {@link NoOpOpsSignalPublisher} 가 주입된다.
 */
public interface OpsSignalPort {

    /** 완성된 envelope 발행. */
    void emit(OpsSignal signal);

    /**
     * 편의 발행 — service/severity/occurredAt 을 구현이 채운다(severity=ERROR, occurredAt=now).
     *
     * @param attributes 비식별 메타 (null 이면 빈 맵)
     */
    void emit(OpsSignalCategory category, String entityType, String entityId, Map<String, Object> attributes);
}
