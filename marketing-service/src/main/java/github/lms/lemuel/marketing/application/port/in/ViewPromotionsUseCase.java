package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.application.port.dto.PromotionSummary;

import java.time.LocalDate;
import java.util.List;

/** 진행 중인 프로모션 통합 목록. */
public interface ViewPromotionsUseCase {
    List<PromotionSummary> runningOn(LocalDate on);
}
