package github.lms.lemuel.sellertier.domain;

/** 평가 결과. GUARDED 는 "강등 조건이지만 아직 내리지 않음" — 이력에 남겨 이유를 설명할 수 있게 한다. */
public enum TierOutcome {
    PROMOTED,
    DEMOTED,
    HELD,
    GUARDED
}
