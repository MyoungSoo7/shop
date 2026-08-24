package github.lms.lemuel.point.application.port.in;

import java.math.BigDecimal;

/**
 * 포인트 사용(차감) 유스케이스 — 결제의 POINT 텐더가 부르는 진입점.
 *
 * <p>{@code userId} 는 반드시 <b>JWT 주체에서 파생</b>한 값이어야 한다. 요청 본문의 userId 를
 * 그대로 넘기면 남의 포인트로 결제할 수 있다(IDOR).
 */
public interface UsePointUseCase {

    /**
     * @param userId        포인트 소유자 — JWT 주체에서 파생
     * @param amount        사용액(양수, 1원 단위 정수)
     * @param referenceType 사용 근거 종류(예: {@code PAYMENT_TENDER})
     * @param referenceId   사용 근거 식별자(예: tenderId)
     * @param actor         감사용 실행 주체
     */
    record UsePointCommand(Long userId, BigDecimal amount, String referenceType,
                           String referenceId, String actor) {
    }

    record UsePointResult(Long entryId, BigDecimal usedAmount, BigDecimal remainingBalance) {
    }

    UsePointResult use(UsePointCommand command);
}
