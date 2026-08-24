package github.lms.lemuel.point.application.port.in;

import java.math.BigDecimal;

/**
 * 수기 차감 — 운영자가 오지급·부정 적립을 거둬들인다. 수기 지급({@link GrantPointUseCase})의 역방향.
 *
 * <p>지급과 대칭으로 설계한다: <b>사유 필수</b>(근거 없이 사라진 돈은 방어할 수 없다),
 * <b>멱등 키 필수</b>(같은 회수를 두 번 눌러도 한 번만 빠져야 한다).
 *
 * <p>주문 취소로 인한 적립 회수({@link RevokeOrderPointUseCase})와는 다른 경로다. 저쪽은
 * "그 주문이 만든 로트"만 되가져오고 시스템이 금액을 계산하지만, 여기는 운영자가 금액을 정하고
 * 소비 순서(만료 임박 순)를 따른다.
 */
public interface DeductPointUseCase {

    DeductPointResult deduct(DeductPointCommand command);

    /**
     * @param referenceId 멱등 키 — 같은 값으로 두 번 호출해도 한 번만 차감된다(원장 자연키)
     * @param reason      차감 근거. 원장 메모로 영구 보존된다
     */
    record DeductPointCommand(Long userId, BigDecimal amount, String referenceId,
                              String reason, String actor) {
    }

    /** @param entryId null 이면 같은 referenceId 로 이미 차감된 건이다(멱등 단축 반환) */
    record DeductPointResult(Long entryId, BigDecimal deductedAmount, BigDecimal remainingBalance) {
    }
}
