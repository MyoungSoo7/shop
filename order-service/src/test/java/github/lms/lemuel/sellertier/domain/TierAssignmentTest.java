package github.lms.lemuel.sellertier.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등급 전이 판정 — 승급은 즉시, 강등은 유예 (ADR 0031 §3).
 *
 * <p>강등은 셀러에게 수수료 인상·지급 지연·홀드백 증가가 한꺼번에 오는 사건이다. 유예 없이 자동화하면
 * 임계 근처 셀러가 매달 등급을 왕복하며 경제조건이 출렁인다(등급 진동). 반대로 승급까지 미루면
 * 셀러가 조건을 충족했는데도 혜택이 늦어져 불만이 된다 — 그래서 두 방향을 다르게 다룬다.
 */
class TierAssignmentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);
    private static final int MISS_THRESHOLD = 2;

    private TierAssignment normal() {
        return TierAssignment.initial(7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6));
    }

    private TierAssignment vipWithGuard(LocalDate guardUntil, int misses) {
        return TierAssignment.rehydrate(7L, SellerTierGrade.VIP, TODAY.minusMonths(6), guardUntil, misses);
    }

    // ── 승급 ──

    @Test @DisplayName("조건을 만족하면 즉시 승급한다")
    void promotesImmediately() {
        TierDecision d = normal().decide(SellerTierGrade.VIP, TODAY, MISS_THRESHOLD);

        assertThat(d.outcome()).isEqualTo(TierOutcome.PROMOTED);
        assertThat(d.targetTier()).isEqualTo(SellerTierGrade.VIP);
    }

    @Test @DisplayName("승급은 강등 유예가 남아 있어도 막히지 않는다 — 유예는 강등만 늦춘다")
    void promotionIgnoresDemotionGuard() {
        TierAssignment guarded = TierAssignment.rehydrate(
                7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6), TODAY.plusMonths(3), 1);

        assertThat(guarded.decide(SellerTierGrade.STRATEGIC, TODAY, MISS_THRESHOLD).outcome())
                .isEqualTo(TierOutcome.PROMOTED);
    }

    @Test @DisplayName("승급하면 미달 카운트가 초기화된다 — 과거 미달이 다음 강등에 이월되지 않게")
    void promotionResetsMissCount() {
        TierAssignment a = vipWithGuard(TODAY.minusDays(1), 1);

        a.apply(a.decide(SellerTierGrade.STRATEGIC, TODAY, MISS_THRESHOLD), TODAY);

        assertThat(a.getConsecutiveMissCount()).isZero();
    }

    // ── 유지 ──

    @Test @DisplayName("같은 등급이면 유지 — 아무 것도 바꾸지 않는다")
    void sameTierHolds() {
        assertThat(normal().decide(SellerTierGrade.NORMAL, TODAY, MISS_THRESHOLD).outcome())
                .isEqualTo(TierOutcome.HELD);
    }

    @Test @DisplayName("등급을 유지하면 미달 카운트도 초기화된다")
    void holdResetsMissCount() {
        TierAssignment a = vipWithGuard(TODAY.minusDays(1), 1);

        a.apply(a.decide(SellerTierGrade.VIP, TODAY, MISS_THRESHOLD), TODAY);

        assertThat(a.getConsecutiveMissCount()).isZero();
    }

    // ── 강등: 두 조건을 모두 만족해야 한다 ──

    @Test @DisplayName("유예 기간이 남아 있으면 강등하지 않고 보류로 기록한다")
    void guardBlocksDemotion() {
        TierAssignment a = vipWithGuard(TODAY.plusMonths(1), MISS_THRESHOLD);

        TierDecision d = a.decide(SellerTierGrade.NORMAL, TODAY, MISS_THRESHOLD);

        assertThat(d.outcome()).isEqualTo(TierOutcome.GUARDED);
        assertThat(d.reason()).contains("유예");
    }

    @Test @DisplayName("유예가 끝나도 연속 미달이 모자라면 강등하지 않는다")
    void insufficientMissesBlocksDemotion() {
        TierAssignment a = vipWithGuard(TODAY.minusDays(1), 0);

        TierDecision d = a.decide(SellerTierGrade.NORMAL, TODAY, MISS_THRESHOLD);

        assertThat(d.outcome()).isEqualTo(TierOutcome.GUARDED);
    }

    @Test @DisplayName("보류는 미달 카운트를 올린다 — 연속 미달이 쌓여야 강등이 된다")
    void guardIncrementsMissCount() {
        TierAssignment a = vipWithGuard(TODAY.minusDays(1), 0);

        a.apply(a.decide(SellerTierGrade.NORMAL, TODAY, MISS_THRESHOLD), TODAY);

        assertThat(a.getConsecutiveMissCount()).isEqualTo(1);
        assertThat(a.getTier()).isEqualTo(SellerTierGrade.VIP);   // 등급은 그대로
    }

    @Test @DisplayName("유예가 끝나고 연속 미달도 채우면 강등한다")
    void demotesWhenBothConditionsMet() {
        TierAssignment a = vipWithGuard(TODAY.minusDays(1), MISS_THRESHOLD);

        TierDecision d = a.decide(SellerTierGrade.NORMAL, TODAY, MISS_THRESHOLD);

        assertThat(d.outcome()).isEqualTo(TierOutcome.DEMOTED);
        assertThat(d.targetTier()).isEqualTo(SellerTierGrade.NORMAL);
    }

    @Test @DisplayName("유예 만료일 당일은 아직 유예 중이다 — 지나야 강등")
    void guardExpiryDayIsStillGuarded() {
        TierAssignment a = vipWithGuard(TODAY, MISS_THRESHOLD);

        assertThat(a.decide(SellerTierGrade.NORMAL, TODAY, MISS_THRESHOLD).outcome())
                .isEqualTo(TierOutcome.GUARDED);
    }

    @Test @DisplayName("유예가 설정된 적 없으면(null) 연속 미달만으로 강등한다")
    void nullGuardMeansNoGuard() {
        TierAssignment a = TierAssignment.rehydrate(
                7L, SellerTierGrade.VIP, TODAY.minusMonths(6), null, MISS_THRESHOLD);

        assertThat(a.decide(SellerTierGrade.NORMAL, TODAY, MISS_THRESHOLD).outcome())
                .isEqualTo(TierOutcome.DEMOTED);
    }

    @Test @DisplayName("강등하면 미달 카운트가 초기화된다 — 다음 강등은 다시 처음부터 센다")
    void demotionResetsMissCount() {
        TierAssignment a = vipWithGuard(TODAY.minusDays(1), MISS_THRESHOLD);

        a.apply(a.decide(SellerTierGrade.NORMAL, TODAY, MISS_THRESHOLD), TODAY);

        assertThat(a.getTier()).isEqualTo(SellerTierGrade.NORMAL);
        assertThat(a.getConsecutiveMissCount()).isZero();
    }

    // ── 관리자 지정 ──

    @Test @DisplayName("관리자 지정은 유예를 새로 건다 — 수동 승급이 다음 평가에서 곧바로 뒤집히지 않게")
    void overrideResetsGuard() {
        TierAssignment a = normal();

        a.overrideTo(SellerTierGrade.STRATEGIC, TODAY, 3);

        assertThat(a.getTier()).isEqualTo(SellerTierGrade.STRATEGIC);
        assertThat(a.getDemotionGuardUntil()).isEqualTo(TODAY.plusMonths(3));
        assertThat(a.getConsecutiveMissCount()).isZero();
    }

    @Test @DisplayName("관리자 지정 직후에는 강등이 유예로 막힌다")
    void overrideProtectsFromImmediateDemotion() {
        TierAssignment a = normal();
        a.overrideTo(SellerTierGrade.STRATEGIC, TODAY, 3);

        assertThat(a.decide(SellerTierGrade.NORMAL, TODAY.plusDays(1), 1).outcome())
                .isEqualTo(TierOutcome.GUARDED);
    }
}
