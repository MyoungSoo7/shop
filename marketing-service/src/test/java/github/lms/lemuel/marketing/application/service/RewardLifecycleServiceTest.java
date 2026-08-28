package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import github.lms.lemuel.marketing.application.port.out.PublishRewardRequestedPort;
import github.lms.lemuel.marketing.application.port.out.RewardGrantPort;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.RewardStatus;
import github.lms.lemuel.marketing.support.MarketingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보상 왕복의 뒷단 둘 — 적립 확인과 일괄 지급 정산.
 *
 * <p>확인 쪽에서 중요한 것은 <b>모르는 id 를 조용히 넘기는 것</b>이다.
 * {@code lemuel.point.granted} 에는 주문 적립·수동 지급 등 마케팅과 무관한 적립도 전부 실려 온다.
 * 그걸 예외로 만들면 컨슈머가 남의 이벤트마다 재시도하다 DLQ 로 간다.
 */
class RewardLifecycleServiceTest {

    private static RewardGrant scheduled(UUID campaignId, LocalDate on, String memo) {
        return RewardGrant.scheduled(UUID.randomUUID(), RewardSource.LUCKYBOX, UUID.randomUUID(), campaignId,
                "member-1", new BigDecimal("500"), null, memo, on);
    }

    @Nested
    class 적립_확인 {

        private RewardGrantPort rewardGrantPort;
        private RewardConfirmationService service;

        @BeforeEach
        void setUp() {
            rewardGrantPort = mock(RewardGrantPort.class);
            service = new RewardConfirmationService(rewardGrantPort);
        }

        @Test
        void null_이면_아무것도_하지_않는다() {
            service.confirm(null);

            verify(rewardGrantPort, never()).findById(any());
        }

        @Test
        void 모르는_id_는_조용히_지나간다() {
            UUID unknown = UUID.randomUUID();
            when(rewardGrantPort.findById(unknown)).thenReturn(Optional.empty());

            service.confirm(unknown);

            verify(rewardGrantPort, never()).save(any());
        }

        @Test
        void 우리_보상이면_CONFIRMED_로_넘기고_저장한다() {
            RewardGrant grant = RewardGrant.requestNow(UUID.randomUUID(), RewardSource.ATTENDANCE_DAILY,
                    UUID.randomUUID(), UUID.randomUUID(), "member-1", BigDecimal.TEN, null, "메모");
            when(rewardGrantPort.findById(grant.id())).thenReturn(Optional.of(grant));

            service.confirm(grant.id());

            assertEquals(RewardStatus.CONFIRMED, grant.status());
            verify(rewardGrantPort).save(grant);
        }

        @Test
        void 같은_통지가_두_번_와도_예외가_아니다() {
            RewardGrant grant = RewardGrant.requestNow(UUID.randomUUID(), RewardSource.ATTENDANCE_DAILY,
                    UUID.randomUUID(), UUID.randomUUID(), "member-1", BigDecimal.TEN, null, "메모");
            when(rewardGrantPort.findById(grant.id())).thenReturn(Optional.of(grant));

            service.confirm(grant.id());
            service.confirm(grant.id());

            assertEquals(RewardStatus.CONFIRMED, grant.status());
        }
    }

    @Nested
    class 일괄_지급_정산 {

        private RewardGrantPort rewardGrantPort;
        private PublishRewardRequestedPort publishPort;
        private LoadLuckyboxCampaignPort luckyboxPort;
        private LoadAttendanceCampaignPort attendancePort;
        private RewardSettlementService service;

        @BeforeEach
        void setUp() {
            rewardGrantPort = mock(RewardGrantPort.class);
            publishPort = mock(PublishRewardRequestedPort.class);
            luckyboxPort = mock(LoadLuckyboxCampaignPort.class);
            attendancePort = mock(LoadAttendanceCampaignPort.class);
            service = new RewardSettlementService(rewardGrantPort, publishPort, luckyboxPort, attendancePort);
        }

        @Test
        void 지급_대상이_없으면_0_건이다() {
            LocalDate on = LocalDate.of(2026, 9, 1);
            when(rewardGrantPort.findDue(on, 500)).thenReturn(List.of());

            assertEquals(0, service.settle(on));
            verify(publishPort, never()).rewardRequested(any(), any());
        }

        @Test
        void 대상을_요청으로_넘기고_캠페인_이름을_실어_발행한다() {
            LocalDate on = LocalDate.of(2026, 9, 1);
            LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
            RewardGrant grant = scheduled(campaign.id(), on, "메모");
            when(rewardGrantPort.findDue(on, 500)).thenReturn(List.of(grant));
            when(luckyboxPort.findById(campaign.id())).thenReturn(Optional.of(campaign));

            assertEquals(1, service.settle(on));

            assertEquals(RewardStatus.REQUESTED, grant.status());
            verify(rewardGrantPort).save(grant);
            verify(publishPort).rewardRequested(grant, "8월 럭키박스");
        }

        @Test
        void 럭키박스에_없으면_출석_캠페인에서_이름을_찾는다() {
            LocalDate on = LocalDate.of(2026, 9, 1);
            AttendanceCampaign campaign = MarketingFixtures.runningAttendance();
            RewardGrant grant = scheduled(campaign.id(), on, "메모");
            when(rewardGrantPort.findDue(on, 500)).thenReturn(List.of(grant));
            when(luckyboxPort.findById(campaign.id())).thenReturn(Optional.empty());
            when(attendancePort.findById(campaign.id())).thenReturn(Optional.of(campaign));

            service.settle(on);

            verify(publishPort).rewardRequested(grant, "8월 출석");
        }

        /** 이름 하나 때문에 지급이 막히는 게 더 나쁘다 — 못 찾으면 메모를 쓴다. */
        @Test
        void 캠페인을_못_찾으면_메모를_이름_자리에_쓴다() {
            LocalDate on = LocalDate.of(2026, 9, 1);
            UUID gone = UUID.randomUUID();
            RewardGrant grant = scheduled(gone, on, "사라진 이벤트 [럭키박스 당첨]");
            when(rewardGrantPort.findDue(on, 500)).thenReturn(List.of(grant));
            when(luckyboxPort.findById(gone)).thenReturn(Optional.empty());
            when(attendancePort.findById(gone)).thenReturn(Optional.empty());

            service.settle(on);

            verify(publishPort).rewardRequested(eq(grant), eq("사라진 이벤트 [럭키박스 당첨]"));
        }
    }
}
