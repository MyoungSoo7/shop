package github.lms.lemuel.marketing.application.port.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 출석 결과.
 *
 * <p>{@code rewardPending} 이 true 인 것은 포인트가 아직 안 들어왔다는 뜻이다. 이 서비스는
 * 요청만 내고 적립은 order-service 가 하므로, 응답 시점에는 원장에 반영되기 전이다.
 * 화면은 "적립 요청됨" 으로 보여야 하고, 확정은 {@code lemuel.point.granted} 를 받은 뒤에 뒤집힌다.
 */
public record CheckInResultView(
        LocalDate attendedOn,
        BigDecimal dailyRewardPoints,
        int attendedTotal,
        int attendedStreak,
        boolean goalReached,
        BigDecimal goalRewardPoints,
        boolean rewardPending
) {
}
