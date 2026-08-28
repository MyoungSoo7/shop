package github.lms.lemuel.marketing.domain;

import github.lms.lemuel.marketing.domain.exception.CampaignNotOpenException;
import github.lms.lemuel.marketing.support.MarketingFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 럭키박스 캠페인 애그리거트.
 *
 * <p>핵심은 둘이다. 일괄 지급인데 지급일이 없으면 <b>만들 수 없어야</b> 하고(당첨은 보이는데
 * 포인트는 영원히 안 들어오는 상태), 참여 슬롯 키는 참여 조건에 따라 범위가 달라져야 한다.
 */
class LuckyboxCampaignTest {

    private static final LocalDate START = MarketingFixtures.START;
    private static final LocalDate END = MarketingFixtures.END;

    private static LuckyboxCampaign draft(String name, LocalDate startsOn, LocalDate endsOn,
                                          BenefitType benefitType, LocalDate benefitOn,
                                          EntryCondition entryCondition) {
        return LuckyboxCampaign.draft(UUID.randomUUID(), "tenant-1", name, startsOn, endsOn, benefitType,
                benefitOn, entryCondition, null, "안내", null, "admin");
    }

    // ------------------------------------------------------------ 생성 규칙

    @Test
    void 초안은_DRAFT_로_만들어진다() {
        LuckyboxCampaign campaign = draft("8월 럭키박스", START, END,
                BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY);

        assertEquals(CampaignStatus.DRAFT, campaign.status());
        assertEquals("tenant-1", campaign.tenantRef());
        assertEquals("admin", campaign.createdBy());
        assertEquals("안내", campaign.note());
        assertEquals(CampaignBanner.empty(), campaign.banner());
        assertEquals(0L, campaign.version());
    }

    @Test
    void id_와_이름과_기간을_검사한다() {
        assertThrows(IllegalArgumentException.class, () -> LuckyboxCampaign.draft(
                null, "t", "이름", START, END, BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY,
                null, null, null, "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> draft("  ", START, END, BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY));
        assertThrows(IllegalArgumentException.class,
                () -> draft("이름", null, END, BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY));
        assertThrows(IllegalArgumentException.class,
                () -> draft("이름", START, null, BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY));
        assertThrows(IllegalArgumentException.class,
                () -> draft("이름", END, START, BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY));
    }

    @Test
    void 지급_방식과_참여_조건은_필수다() {
        assertThrows(IllegalArgumentException.class,
                () -> draft("이름", START, END, null, null, EntryCondition.PER_DAY));
        assertThrows(IllegalArgumentException.class,
                () -> draft("이름", START, END, BenefitType.IMMEDIATE, null, null));
    }

    /** 지급일 없는 일괄 지급 = 당첨은 보이고 포인트는 영원히 안 들어오는 캠페인. */
    @Test
    void 일괄_지급인데_지급일이_없으면_거절한다() {
        assertThrows(IllegalArgumentException.class,
                () -> draft("이름", START, END, BenefitType.BATCH, null, EntryCondition.PER_PERIOD));
    }

    // ------------------------------------------------------------ 참여 가능 여부

    @Test
    void 진행_중이_아니면_거절한다() {
        LuckyboxCampaign campaign = MarketingFixtures.luckybox(CampaignStatus.DRAFT, BenefitType.IMMEDIATE,
                null, EntryCondition.PER_DAY, START, END);

        CampaignNotOpenException e = assertThrows(CampaignNotOpenException.class,
                () -> campaign.assertDrawAllowed(START));
        assertTrue(e.getMessage().contains("진행 중인 이벤트가 아닙니다"));
    }

    @Test
    void 기간_밖이면_거절한다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();

        assertThrows(CampaignNotOpenException.class, () -> campaign.assertDrawAllowed(START.minusDays(1)));
        assertThrows(CampaignNotOpenException.class, () -> campaign.assertDrawAllowed(END.plusDays(1)));
        campaign.assertDrawAllowed(START);
        campaign.assertDrawAllowed(END);
    }

    // ------------------------------------------------------------ 슬롯·지급일

    @Test
    void 참여_슬롯은_조건에_따라_범위가_다르다() {
        LocalDate day = LocalDate.of(2026, 8, 12);

        assertEquals("2026-08-12", MarketingFixtures.runningLuckybox().entrySlot(day));
        assertEquals("ALL", MarketingFixtures.luckybox(CampaignStatus.RUNNING, BenefitType.IMMEDIATE, null,
                EntryCondition.PER_PERIOD, START, END).entrySlot(day));
    }

    @Test
    void 즉시_지급은_예정일이_없다() {
        assertNull(MarketingFixtures.runningLuckybox().scheduledRewardDate());
    }

    @Test
    void 일괄_지급은_지급일이_예정일이다() {
        LocalDate benefitOn = END.plusDays(3);
        LuckyboxCampaign campaign = MarketingFixtures.luckybox(CampaignStatus.RUNNING, BenefitType.BATCH,
                benefitOn, EntryCondition.PER_PERIOD, START, END);

        assertEquals(benefitOn, campaign.scheduledRewardDate());
        assertEquals(benefitOn, campaign.benefitOn());
    }

    // ------------------------------------------------------------ 상태 전이·수정

    @Test
    void 개시하면_RUNNING_이_된다() {
        LuckyboxCampaign campaign = draft("이름", START, END,
                BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY);

        campaign.open("operator-2");

        assertEquals(CampaignStatus.RUNNING, campaign.status());
        assertEquals("operator-2", campaign.updatedBy());
    }

    @Test
    void 종료된_이벤트는_다시_열_수_없다() {
        LuckyboxCampaign campaign = draft("이름", START, END,
                BenefitType.IMMEDIATE, null, EntryCondition.PER_DAY);
        campaign.close("operator-2");

        assertEquals(CampaignStatus.CLOSED, campaign.status());
        assertThrows(CampaignNotOpenException.class, () -> campaign.open("operator-3"));
    }

    @Test
    void 수정은_기간과_지급일과_노출만_바꾼다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();
        LocalDate newEnd = END.plusDays(5);
        LocalDate expiresOn = LocalDate.of(2027, 1, 31);

        campaign.update("연장판", START, newEnd, null, expiresOn, "연장 안내",
                CampaignBanner.of(null, "mo2.png"), "operator-9");

        assertEquals("연장판", campaign.name());
        assertEquals(newEnd, campaign.endsOn());
        assertEquals(expiresOn, campaign.rewardExpiresOn());
        assertEquals("연장 안내", campaign.note());
        assertEquals("mo2.png", campaign.banner().mobileImageUrl());
        assertEquals("operator-9", campaign.updatedBy());
        // 참여 조건·지급 방식은 이미 쌓인 슬롯 키의 의미가 소급해 달라지므로 못 바꾼다.
        assertEquals(EntryCondition.PER_DAY, campaign.entryCondition());
        assertEquals(BenefitType.IMMEDIATE, campaign.benefitType());
    }

    @Test
    void 수정도_이름과_기간과_일괄_지급일을_다시_검사한다() {
        LuckyboxCampaign immediate = MarketingFixtures.runningLuckybox();
        assertThrows(IllegalArgumentException.class,
                () -> immediate.update(null, START, END, null, null, null, null, "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> immediate.update("이름", END, START, null, null, null, null, "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> immediate.update("이름", null, END, null, null, null, null, "admin"));

        LuckyboxCampaign batch = MarketingFixtures.luckybox(CampaignStatus.RUNNING, BenefitType.BATCH,
                END.plusDays(1), EntryCondition.PER_PERIOD, START, END);
        assertThrows(IllegalArgumentException.class,
                () -> batch.update("이름", START, END, null, null, null, null, "admin"));
    }

    @Test
    void 수정에서_배너가_null_이면_빈_값이_된다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();

        campaign.update("이름", START, END, null, null, null, null, "admin");

        assertEquals(CampaignBanner.empty(), campaign.banner());
        assertNull(campaign.note());
    }

    @Test
    void 되살린_캠페인은_버전을_그대로_들고_온다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();

        assertEquals(2L, campaign.version());
    }

    /**
     * 참여 자격은 이 둘이 전부다 — 상태와 기간. 한때 가입일·주문금액·배송상태 조건이 필드로
     * 있었지만 이 메서드가 읽지 않아 "설정했는데 안 먹는" 상태였고, 그래서 지웠다
     * (docs/plan/marketing-legacy-gap.md §2 ④). 조건을 다시 늘리려면 그 데이터가 이 서비스
     * 안에 있어야 한다는 것을 이 테스트가 상기시킨다.
     */
    @Test
    void 참여_자격은_상태와_기간뿐이다() {
        LuckyboxCampaign campaign = MarketingFixtures.runningLuckybox();

        assertDoesNotThrow(() -> campaign.assertDrawAllowed(START));
        assertDoesNotThrow(() -> campaign.assertDrawAllowed(END));
        assertThrows(CampaignNotOpenException.class, () -> campaign.assertDrawAllowed(START.minusDays(1)));
        assertThrows(CampaignNotOpenException.class, () -> campaign.assertDrawAllowed(END.plusDays(1)));
    }
}
