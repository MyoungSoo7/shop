package github.lms.lemuel.point.application.port.in;

import java.math.BigDecimal;

/**
 * 포인트 환불 복원 유스케이스 — 결제 환불의 내부잔액 텐더가 부르는 진입점.
 *
 * <p>복원은 <b>원래 사용했던 로트로 되돌리는 것</b>이 원칙이다. 그래서 원 사용 엔트리를 가리키는
 * 참조({@code sourceReferenceType/Id})와, 이번 복원 자체의 참조({@code referenceType/Id})를 모두 받는다.
 * 전자가 없으면 어느 로트로 돌려줄지 알 수 없고, 후자가 없으면 같은 환불을 두 번 반영하는 걸 막을 수 없다.
 */
public interface RestorePointUseCase {

    /**
     * 복원 대상 계정은 <b>커맨드에 없다</b>. 환불은 낸 사람에게 돌아가야 하므로 원 사용 엔트리에서
     * 계정을 도출한다 — 호출자가 넘긴 식별자를 믿으면 남의 계정으로 복원할 수 있다.
     *
     * @param amount                복원액(양수, 1원 단위 정수)
     * @param sourceReferenceType   원 사용 엔트리의 참조 종류(예: {@code PAYMENT_TENDER})
     * @param sourceReferenceId     원 사용 엔트리의 참조 식별자(예: tenderId)
     * @param referenceType         이번 복원의 참조 종류(예: {@code PAYMENT_TENDER_REFUND})
     * @param referenceId           이번 복원의 참조 식별자
     * @param actor                 감사용 실행 주체
     */
    record RestorePointCommand(BigDecimal amount,
                               String sourceReferenceType, String sourceReferenceId,
                               String referenceType, String referenceId, String actor) {
    }

    record RestorePointResult(Long entryId, BigDecimal restoredAmount, BigDecimal remainingBalance) {
    }

    RestorePointResult restore(RestorePointCommand command);
}
