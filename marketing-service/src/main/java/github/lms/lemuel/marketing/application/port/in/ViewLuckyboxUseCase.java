package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.application.port.dto.LuckyboxBoardView;

import java.time.LocalDate;
import java.util.UUID;

/** 럭키박스 현황 조회. */
public interface ViewLuckyboxUseCase {
    LuckyboxBoardView board(UUID campaignId, String memberRef, LocalDate on);
}
