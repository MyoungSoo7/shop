package github.lms.lemuel.expirynotice.domain;

import java.time.Duration;

/**
 * 통보 단계 — 만료 며칠 전에 보내는가.
 *
 * <p>단계를 값으로 둔 이유는 <b>멱등 키의 일부</b>이기 때문이다. 같은 포인트 로트에 D-30 과 D-7 을
 * 각각 한 번씩 보내야 하는데, 키가 대상뿐이면 둘 중 하나만 나간다.
 *
 * <p>단계별 창은 <b>겹치지 않는다</b> — D-7 조회는 "7일 이내" 가 아니라 "7일 이내이면서 1일 초과" 다.
 * 겹치게 두면 하루에 두 통이 나가고, 사용자에게는 그게 곧 스팸이다.
 */
public enum ExpiryNoticeStage {

    /** 만료 30일 전 — 쓸 계획을 세울 수 있는 거리. */
    D30(Duration.ofDays(30), Duration.ofDays(7)),

    /** 만료 7일 전 — 실제로 행동하게 만드는 거리. */
    D7(Duration.ofDays(7), Duration.ofDays(1)),

    /** 만료 하루 전 — 마지막. */
    D1(Duration.ofDays(1), Duration.ZERO);

    private final Duration leadTime;
    private final Duration nextStageLeadTime;

    ExpiryNoticeStage(Duration leadTime, Duration nextStageLeadTime) {
        this.leadTime = leadTime;
        this.nextStageLeadTime = nextStageLeadTime;
    }

    /** 이 단계가 담당하는 만료 시각의 상한(배제) — {@code asOf + leadTime}. */
    public Duration leadTime() {
        return leadTime;
    }

    /** 이 단계가 담당하는 만료 시각의 하한(포함) — {@code asOf + nextStageLeadTime}. 마지막 단계는 asOf 자신이다. */
    public Duration floorLeadTime() {
        return nextStageLeadTime;
    }
}
