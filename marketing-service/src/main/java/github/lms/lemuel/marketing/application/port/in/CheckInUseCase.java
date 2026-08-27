package github.lms.lemuel.marketing.application.port.in;

import github.lms.lemuel.marketing.application.port.dto.CheckInResultView;

import java.time.LocalDate;
import java.util.UUID;

/** 출석하기. */
public interface CheckInUseCase {
    CheckInResultView checkIn(UUID campaignId, String memberRef, LocalDate on);
}
