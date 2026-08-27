package github.lms.lemuel.marketing.domain;

/**
 * 보상 요청의 상태.
 *
 * <p>이 서비스는 포인트를 지급하지 않는다. 그래서 "얼마를 줬다" 가 아니라 "요청했고, 원장이
 * 받았다고 알려 왔다" 를 기록한다. CONFIRMED 로 넘기는 근거는 order-service 가 내보내는
 * {@code lemuel.point.granted} 이고, 그게 오기 전까지는 화면에 "적립 처리 중" 으로 보인다.
 *
 * <pre>
 *   PENDING ──(즉시 지급 / 정산 스케줄러)──▶ REQUESTED ──(point.granted 수신)──▶ CONFIRMED
 *                                                │
 *                                                └──(원장이 거절)──▶ FAILED
 * </pre>
 */
public enum RewardStatus {

    /** 확정됐지만 아직 요청 전. 일괄 지급 캠페인의 대기분이 여기 있다. */
    PENDING,

    /** outbox 에 요청 이벤트를 실었다. */
    REQUESTED,

    /** 원장에 실제로 적립됐다. */
    CONFIRMED,

    /** 원장이 거절했다 — 사유는 failureReason 에 남는다. */
    FAILED
}
