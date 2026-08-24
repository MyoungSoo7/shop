package github.lms.lemuel.point.domain;

import java.math.RoundingMode;

/**
 * 적립액 라운딩 방식 — 적립 단위({@code roundingUnit}) 경계에서 어느 쪽으로 맞출지.
 *
 * <p>{@link java.math.RoundingMode} 를 그대로 정책 컬럼에 저장하지 않는 이유: RoundingMode 에는
 * 금액 정책으로 쓰면 안 되는 값(UNNECESSARY, HALF_EVEN 등)이 섞여 있어 DB 에 무엇이든 들어올 수
 * 있게 된다. 판촉비 방향을 바꾸는 선택지는 세 개면 충분하고, 그 셋만 이름으로 열어 둔다.
 */
public enum PointEarnRounding {
    /** 버림 — 회사가 약속하지 않은 1 원을 주지 않는다(기본값). */
    DOWN(RoundingMode.DOWN),
    /** 반올림 — 단위 절반을 기준으로 가까운 쪽. */
    HALF_UP(RoundingMode.HALF_UP),
    /** 올림 — 고객에게 유리하게 한 단위를 채워 준다. */
    UP(RoundingMode.UP);

    private final RoundingMode mode;

    PointEarnRounding(RoundingMode mode) {
        this.mode = mode;
    }

    public RoundingMode toRoundingMode() {
        return mode;
    }
}
