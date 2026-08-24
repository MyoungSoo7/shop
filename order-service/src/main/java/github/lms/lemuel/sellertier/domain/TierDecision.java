package github.lms.lemuel.sellertier.domain;

/** 평가 1건의 판정 — 무엇으로 갈지와 그 이유. */
public record TierDecision(TierOutcome outcome, SellerTierGrade targetTier, String reason) {
}
