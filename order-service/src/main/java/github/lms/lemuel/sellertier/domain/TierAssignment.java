package github.lms.lemuel.sellertier.domain;

import java.time.LocalDate;

/**
 * 셀러의 현재 등급과 그 전이 규칙 (ADR 0031 §3).
 *
 * <p><b>승급과 강등을 다르게 다룬다.</b> 강등은 셀러에게 수수료 인상·지급 지연·홀드백 증가가 한꺼번에
 * 오는 사건이라, 임계 근처에서 매달 오르내리면 경제조건이 출렁인다(등급 진동). 그래서 강등만
 * 유예 기간과 연속 미달 두 조건을 모두 요구한다. 반대로 승급까지 미루면 조건을 채운 셀러의 혜택이
 * 늦어지므로 승급은 즉시 반영한다.
 *
 * <p>강등 조건이지만 아직 내리지 않은 경우는 {@link TierOutcome#GUARDED} 로 남긴다 — 이력에 이유가
 * 남아야 "왜 이 셀러는 안 내려갔나"에 답할 수 있다.
 *
 * <p>setter 는 없다. 상태는 {@link #apply}·{@link #overrideTo} 전이 메서드로만 바뀐다.
 */
public class TierAssignment {

    private final Long sellerId;
    private SellerTierGrade tier;
    private LocalDate effectiveFrom;
    private LocalDate demotionGuardUntil;
    private int consecutiveMissCount;

    private TierAssignment(Long sellerId, SellerTierGrade tier, LocalDate effectiveFrom,
                           LocalDate demotionGuardUntil, int consecutiveMissCount) {
        this.sellerId = sellerId;
        this.tier = tier;
        this.effectiveFrom = effectiveFrom;
        this.demotionGuardUntil = demotionGuardUntil;
        this.consecutiveMissCount = consecutiveMissCount;
    }

    public static TierAssignment initial(Long sellerId, SellerTierGrade tier, LocalDate effectiveFrom) {
        return new TierAssignment(sellerId, tier, effectiveFrom, null, 0);
    }

    public static TierAssignment rehydrate(Long sellerId, SellerTierGrade tier, LocalDate effectiveFrom,
                                           LocalDate demotionGuardUntil, int consecutiveMissCount) {
        return new TierAssignment(sellerId, tier, effectiveFrom, demotionGuardUntil, consecutiveMissCount);
    }

    /**
     * 목표 등급과 대조해 무엇을 할지 정한다 — 상태는 바꾸지 않는다(미리보기와 실행이 같은 판정을 쓰게).
     *
     * @param missThreshold 강등에 필요한 연속 미달 횟수
     */
    public TierDecision decide(SellerTierGrade target, LocalDate today, int missThreshold) {
        if (target == null || target == tier) {
            return new TierDecision(TierOutcome.HELD, tier, null);
        }
        if (target.isHigherThan(tier)) {
            return new TierDecision(TierOutcome.PROMOTED, target, "상위 등급 조건 충족");
        }
        // 이하 강등 방향 — 두 조건을 모두 만족해야 내린다.
        if (isGuarded(today)) {
            return new TierDecision(TierOutcome.GUARDED, tier,
                    "강등 유예 기간(" + demotionGuardUntil + ")이 남아 등급을 유지합니다");
        }
        int nextMisses = consecutiveMissCount + 1;
        if (nextMisses < missThreshold) {
            return new TierDecision(TierOutcome.GUARDED, tier,
                    "연속 미달 " + nextMisses + "회 — 강등 기준(" + missThreshold + "회) 미만이라 유지합니다");
        }
        return new TierDecision(TierOutcome.DEMOTED, target, "연속 미달 " + nextMisses + "회로 강등");
    }

    /** 판정을 상태에 반영한다. 유예 보류(GUARDED)는 등급을 바꾸지 않고 미달만 쌓는다. */
    public void apply(TierDecision decision, LocalDate today) {
        switch (decision.outcome()) {
            case PROMOTED, DEMOTED -> {
                this.tier = decision.targetTier();
                this.effectiveFrom = today;
                this.consecutiveMissCount = 0;   // 방향이 바뀌었으니 다시 처음부터 센다
                this.demotionGuardUntil = null;
            }
            case GUARDED -> this.consecutiveMissCount++;
            case HELD -> this.consecutiveMissCount = 0;
        }
    }

    /**
     * 관리자 지정 — 유예를 새로 건다. 수동 승급 직후 다음 평가에서 곧바로 뒤집히면 지정 자체가
     * 무의미해지므로, 지정에는 반드시 보호 기간이 따라붙는다.
     */
    public void overrideTo(SellerTierGrade target, LocalDate today, int guardMonths) {
        this.tier = target;
        this.effectiveFrom = today;
        this.demotionGuardUntil = today.plusMonths(guardMonths);
        this.consecutiveMissCount = 0;
    }

    /** 유예 만료일 당일은 아직 유예 중이다 — 지나야 강등할 수 있다. */
    private boolean isGuarded(LocalDate today) {
        return demotionGuardUntil != null && !today.isAfter(demotionGuardUntil);
    }

    public Long getSellerId() { return sellerId; }
    public SellerTierGrade getTier() { return tier; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getDemotionGuardUntil() { return demotionGuardUntil; }
    public int getConsecutiveMissCount() { return consecutiveMissCount; }
}
