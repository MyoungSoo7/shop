package github.lms.lemuel.payment.adapter.out.point;

import github.lms.lemuel.payment.application.port.out.PointTenderPort;
import github.lms.lemuel.point.application.port.in.RestorePointUseCase;
import github.lms.lemuel.point.application.port.in.UsePointUseCase;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 결제 ↔ 포인트 원장 연결 어댑터.
 *
 * <p>결제 모듈이 포인트 유스케이스를 직접 참조하지 않도록 이 어댑터가 경계에 선다. 두 도메인이
 * 같은 서비스·같은 DB 안에 있으므로 호출은 같은 트랜잭션에서 원자적으로 일어난다 — 포인트 원장을
 * 별도 서비스로 쪼개지 않기로 한 결정(docs/plan/point-ledger.md §2)이 여기서 값을 한다.
 */
@Component
public class PointTenderAdapter implements PointTenderPort {

    /** 사용 근거 참조 종류 — 원장 자연키의 일부라 값이 바뀌면 멱등이 깨진다. 상수로 고정한다. */
    private static final String USE_REFERENCE_TYPE = "PAYMENT_TENDER";
    private static final String REFUND_REFERENCE_TYPE = "PAYMENT_TENDER_REFUND";

    private final UsePointUseCase usePointUseCase;
    private final RestorePointUseCase restorePointUseCase;
    private final github.lms.lemuel.point.application.port.in.ManagePointUsageLimitUseCase usageLimitUseCase;
    private final github.lms.lemuel.point.application.port.in.HoldPointUseCase holdUseCase;

    public PointTenderAdapter(UsePointUseCase usePointUseCase, RestorePointUseCase restorePointUseCase,
                              github.lms.lemuel.point.application.port.in.ManagePointUsageLimitUseCase usageLimitUseCase,
                              github.lms.lemuel.point.application.port.in.HoldPointUseCase holdUseCase) {
        this.usePointUseCase = usePointUseCase;
        this.restorePointUseCase = restorePointUseCase;
        this.usageLimitUseCase = usageLimitUseCase;
        this.holdUseCase = holdUseCase;
    }

    @Override
    public void assertWithinUsageLimit(BigDecimal orderAmount, BigDecimal pointAmount) {
        usageLimitUseCase.assertWithinLimit(orderAmount, pointAmount);
    }

    @Override
    public void use(Long userId, BigDecimal amount, Long tenderId) {
        usePointUseCase.use(new UsePointUseCase.UsePointCommand(
                userId, amount, USE_REFERENCE_TYPE, String.valueOf(tenderId), "user:" + userId));
    }

    @Override
    public void hold(Long userId, BigDecimal amount, Long tenderId) {
        holdUseCase.hold(new github.lms.lemuel.point.application.port.in.HoldPointUseCase.HoldCommand(
                userId, amount, USE_REFERENCE_TYPE, String.valueOf(tenderId)));
    }

    @Override
    public void captureHold(Long tenderId, Long actorUserId) {
        // 확정이 남기는 USE 엔트리의 참조는 즉시 결제와 같다(PAYMENT_TENDER:{tenderId}) —
        // 그래야 이후 환불 복원이 경로를 가리지 않고 같은 자연키로 되돌린다.
        holdUseCase.capture(USE_REFERENCE_TYPE, String.valueOf(tenderId), "user:" + actorUserId);
    }

    @Override
    public void releaseHold(Long tenderId, boolean expired) {
        holdUseCase.release(USE_REFERENCE_TYPE, String.valueOf(tenderId), expired);
    }

    @Override
    public void restore(BigDecimal amount, Long tenderId, String refundReference) {
        restorePointUseCase.restore(new RestorePointUseCase.RestorePointCommand(
                amount,
                USE_REFERENCE_TYPE, String.valueOf(tenderId),
                REFUND_REFERENCE_TYPE, refundReference,
                "payment-refund"));
    }
}
