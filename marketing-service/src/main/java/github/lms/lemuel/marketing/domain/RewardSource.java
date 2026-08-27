package github.lms.lemuel.marketing.domain;

/**
 * 보상이 어디서 나왔는지. order-service 포인트 적립의 {@code referenceType} 으로 그대로 실려 간다.
 *
 * <p>이 값과 {@code referenceId}(= reward_grants.id) 의 조합이 원장 쪽 멱등 키다.
 * {@code GrantPointService} 는 (계좌, GRANT, referenceType, referenceId) 가 같은 적립을
 * 두 번 만들지 않는다 — 이벤트가 두 번 도착해도 포인트는 한 번만 들어온다는 뜻이다.
 */
public enum RewardSource {

    /** 출석한 날 주는 일일 보상. */
    ATTENDANCE_DAILY,

    /** 누적/연속 목표를 채웠을 때 주는 보상. */
    ATTENDANCE_GOAL,

    /** 럭키박스 당첨. */
    LUCKYBOX
}
