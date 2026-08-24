package github.lms.lemuel.shipping.application.port.in;

import github.lms.lemuel.shipping.domain.TrackingNumberRegistration;

import java.util.List;

/**
 * 송장 일괄 등록 — 수백 행을 한 번에 출고 처리한다.
 *
 * <p>실행 전 미리보기가 기본이다: 잘못된 행을 먼저 걷어내지 않으면 운영자는 무엇이 반영되고
 * 무엇이 빠졌는지 사후에야 알게 된다.
 */
public interface RegisterTrackingNumbersUseCase {

    /**
     * @param dryRun true 면 아무 것도 출고하지 않고 "적용될 결과"만 산출한다
     */
    BulkTrackingResult register(List<TrackingNumberRegistration> rows, boolean dryRun);

    /**
     * @param applied 적용된(=dryRun 이면 적용될) 행 수
     * @param failed  거절·실패한 행 수 — 0 이 아니면 운영자가 그 행만 고쳐 다시 올린다
     */
    record BulkTrackingResult(int applied, int failed, boolean dryRun, List<ResultLine> lines) { }

    /** 행별 결과 — 실패는 사유를 담는다. */
    record ResultLine(Long orderId, String carrier, String trackingNumber,
                      boolean applied, String reason) { }
}
