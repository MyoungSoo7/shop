package github.lms.lemuel.point.application.port.in;

import github.lms.lemuel.point.domain.PointLotOrigin;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 포인트 적립·충전 유스케이스.
 *
 * <p>충전 원금과 충전 보너스는 <b>각각 한 번씩</b> 호출한다 — 같은 로트에 합치면 GL 상대계정
 * (현금 vs 판촉비)이 섞여 분개를 만들 수 없고, 환불 시 "보너스만 회수"도 불가능해진다.
 */
public interface GrantPointUseCase {

    /**
     * @param expiresAt null 이면 무기한(수기 지급 등)
     */
    record GrantPointCommand(Long userId, BigDecimal amount, PointLotOrigin origin,
                             String referenceType, String referenceId,
                             OffsetDateTime expiresAt, String actor, String memo) {
    }

    record GrantPointResult(Long entryId, Long lotId, BigDecimal grantedAmount,
                            BigDecimal remainingBalance) {
    }

    GrantPointResult grant(GrantPointCommand command);
}
