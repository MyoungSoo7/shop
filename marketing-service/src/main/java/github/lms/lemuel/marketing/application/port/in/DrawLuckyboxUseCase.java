package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.application.port.dto.DrawResultView;

import java.time.LocalDate;
import java.util.UUID;

/** 럭키박스 뽑기. */
public interface DrawLuckyboxUseCase {
    DrawResultView draw(UUID campaignId, String memberRef, LocalDate on);
}
