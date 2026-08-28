package github.lms.lemuel.marketing.application.port.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 추첨 결과.
 *
 * <p>{@code scheduledOn} 이 있으면 일괄 지급 캠페인이라 그날 포인트가 들어온다.
 * null 이면 즉시 요청됐다 — 그래도 원장 반영은 비동기라 바로 잔액에 보이지는 않는다.
 */
public record DrawResultView(
        UUID drawId,
        String prizeType,
        BigDecimal rewardPoints,
        String textReward,
        LocalDate drawnOn,
        LocalDate scheduledOn,
        boolean rewardPending
) {
}
