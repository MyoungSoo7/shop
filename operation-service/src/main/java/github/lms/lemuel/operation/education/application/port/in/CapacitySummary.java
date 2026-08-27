package github.lms.lemuel.operation.education.application.port.in;

import java.util.UUID;

/**
 * 그 과정의 자리 현황.
 *
 * <p>화면이 "정원 30 / 확정 28 / 대기 5" 를 한 줄로 보여 주려면 세 수가 <b>같은 트랜잭션에서</b>
 * 읽혀야 한다 — 화면이 세 번 호출해 조립하면 그 사이에 확정이 들어와도 모른다. 그래서 셋을
 * 따로 노출하지 않고 한 덩어리로 돌려준다.
 *
 * <p>{@code capacity} 가 null 이면 정원 없음이고, 그때 {@link #remaining()} 도 null 이다 —
 * 0 으로 내려보내면 화면이 "마감"으로 읽는다.
 */
public record CapacitySummary(UUID courseId, String courseTitle, Integer capacity,
                              long confirmed, long waiting, long cancelled) {

    public Integer remaining() {
        return capacity == null ? null : (int) Math.max(0, capacity - confirmed);
    }
}
