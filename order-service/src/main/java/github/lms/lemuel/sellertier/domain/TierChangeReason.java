package github.lms.lemuel.sellertier.domain;

/** 등급이 바뀐 사유 — 이력에 남아 "누가 왜 바꿨나"를 설명한다. */
public enum TierChangeReason {
    AUTO_PROMOTION,
    AUTO_DEMOTION,
    ADMIN_OVERRIDE,

    /**
     * 프로젝션 초기 적재 — 등급이 바뀐 것이 아니라 <b>이미 확정된 등급을 소비측에 알리는</b> 재발행이다.
     *
     * <p>승급·강등과 구분하는 이유: 이 사유로 온 통지를 "이때 등급이 바뀌었다"로 읽으면 백필 시각이
     * 등급 변경일로 둔갑한다. 이력 테이블에는 쓰지 않는다(변경이 아니므로).
     */
    BACKFILL
}
