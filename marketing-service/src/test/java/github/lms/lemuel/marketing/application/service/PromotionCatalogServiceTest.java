package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.dto.PromotionKind;
import github.lms.lemuel.marketing.application.port.dto.PromotionSummary;
import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import github.lms.lemuel.marketing.domain.DayTypeRule;
import github.lms.lemuel.marketing.domain.EntryCondition;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.PeriodType;
import github.lms.lemuel.marketing.domain.StreakRule;
import github.lms.lemuel.marketing.support.MarketingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 진행 중인 프로모션 통합 목록 — 저장은 따로, 합치는 것은 조회에서. */
class PromotionCatalogServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    private LoadAttendanceCampaignPort attendancePort;
    private LoadLuckyboxCampaignPort luckyboxPort;
    private PromotionCatalogService service;

    @BeforeEach
    void setUp() {
        attendancePort = mock(LoadAttendanceCampaignPort.class);
        luckyboxPort = mock(LoadLuckyboxCampaignPort.class);
        service = new PromotionCatalogService(attendancePort, luckyboxPort);
    }

    @Test
    void 아무것도_없으면_빈_목록이다() {
        when(attendancePort.findRunningOn(TODAY)).thenReturn(List.of());
        when(luckyboxPort.findRunningOn(TODAY)).thenReturn(List.of());

        assertTrue(service.runningOn(TODAY).isEmpty());
    }

    @Test
    void 두_종류를_합쳐_곧_끝나는_것부터_보여_준다() {
        AttendanceCampaign attendance = MarketingFixtures.runningAttendance();                 // ~8/31
        LuckyboxCampaign luckybox = MarketingFixtures.luckybox(CampaignStatus.RUNNING,
                BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY,
                MarketingFixtures.START, LocalDate.of(2026, 8, 20));                            // ~8/20
        when(attendancePort.findRunningOn(TODAY)).thenReturn(List.of(attendance));
        when(luckyboxPort.findRunningOn(TODAY)).thenReturn(List.of(luckybox));

        List<PromotionSummary> merged = service.runningOn(TODAY);

        assertEquals(2, merged.size());
        assertEquals(PromotionKind.LUCKYBOX, merged.get(0).kind());
        assertEquals(luckybox.id(), merged.get(0).id());
        assertEquals("8월 럭키박스", merged.get(0).name());
        assertEquals("pc.png", merged.get(0).pcImageUrl());
        assertEquals("mo.png", merged.get(0).mobileImageUrl());
        assertEquals(PromotionKind.ATTENDANCE, merged.get(1).kind());
        assertEquals(attendance.id(), merged.get(1).id());
        assertEquals(MarketingFixtures.START, merged.get(1).startsOn());
        assertEquals(MarketingFixtures.END, merged.get(1).endsOn());
    }

    /** 종료일이 같으면 이름 순 — 정렬이 없으면 매 호출마다 배너 순서가 달라진다. */
    @Test
    void 종료일이_같으면_이름_순이다() {
        AttendanceCampaign later = MarketingFixtures.attendance(CampaignStatus.RUNNING, PeriodType.DAILY,
                StreakRule.EVERY_DAY, 0, DayTypeRule.EVERY_DAY, BigDecimal.ZERO, BigDecimal.ZERO,
                MarketingFixtures.START, MarketingFixtures.END);   // "8월 출석"
        LuckyboxCampaign earlier = MarketingFixtures.runningLuckybox();                        // "8월 럭키박스"
        when(attendancePort.findRunningOn(TODAY)).thenReturn(List.of(later));
        when(luckyboxPort.findRunningOn(TODAY)).thenReturn(List.of(earlier));

        List<PromotionSummary> merged = service.runningOn(TODAY);

        assertEquals("8월 럭키박스", merged.get(0).name());
        assertEquals("8월 출석", merged.get(1).name());
    }
}
