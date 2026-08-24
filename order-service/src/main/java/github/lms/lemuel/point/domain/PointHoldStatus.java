package github.lms.lemuel.point.domain;

/**
 * 포인트 선점(hold) 상태.
 *
 * <p>전이: {@code ACTIVE → CAPTURED}(입금 확인 → 실제 차감) / {@code ACTIVE → RELEASED}(주문 취소 등
 * 명시적 해제) / {@code ACTIVE → EXPIRED}(입금 기한 경과로 자동 해제). 종단 상태에서 되살리지 않는다 —
 * 되돌릴 일이 있으면 새 선점을 만든다({@link PointLotStatus} 와 같은 이유).
 *
 * <p>RELEASED 와 EXPIRED 는 잔고 효과가 같다(선점분이 가용으로 돌아온다). 그래도 나눈 이유는
 * <b>왜 풀렸는지</b>가 운영 판단을 가르기 때문이다 — 만료가 몰리면 입금 안내나 기한 정책을 봐야 하고,
 * 취소가 몰리면 주문 쪽을 봐야 한다. 한 값으로 합치면 그 구분이 사라진다.
 */
public enum PointHoldStatus {
    ACTIVE,
    CAPTURED,
    RELEASED,
    EXPIRED;

    /** 아직 잔고를 붙잡고 있는가. */
    public boolean holdsBalance() {
        return this == ACTIVE;
    }

    /** 더 이상 전이하지 않는 종단 상태인가. */
    public boolean isTerminal() {
        return this != ACTIVE;
    }

    /** 허용 전이인지 — ACTIVE 에서 종단 셋 중 하나로만 간다. */
    public boolean canTransitionTo(PointHoldStatus target) {
        return this == ACTIVE && target != null && target.isTerminal();
    }
}
