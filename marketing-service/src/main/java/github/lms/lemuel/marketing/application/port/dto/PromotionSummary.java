package github.lms.lemuel.marketing.application.port.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 진행 중인 프로모션 한 줄 — 출석과 럭키박스를 한 목록에 합칠 때 쓰는 읽기 모델.
 *
 * <p>두 캠페인을 공통 상위 테이블로 묶지 않은 대신, 합치는 일을 조회 시점에 한다. 공유하는
 * 것이 이름·기간·상태·배너뿐이라 조인 비용을 상시로 무는 것보다 이쪽이 싸다.
 */
public record PromotionSummary(
        PromotionKind kind,
        UUID id,
        String name,
        LocalDate startsOn,
        LocalDate endsOn,
        String pcImageUrl,
        String mobileImageUrl
) {
}
