package github.lms.lemuel.order.application.port.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 작업 큐 집계 조회.
 *
 * <p>큐 묶음(어느 상태가 한 칸인지)은 이 계층의 관심사가 아니다. 여기는 <b>상태 하나당 한 줄</b>을
 * 돌려주고, 묶는 일은 서비스가 한다 — 묶음이 바뀔 때마다 SQL 을 고치지 않기 위해서다.
 */
public interface LoadOrderQueuePort {

    /**
     * 상태별 대기 집계.
     *
     * @param deadlineByStatus 상태 → 기한 초과 기준 시각. 이 시각보다 <b>먼저</b> 그 상태가 된 주문이
     *                         기한 초과다. 상태마다 SLA 가 달라 값도 상태마다 다르다.
     *                         비어 있으면 빈 목록을 돌려준다(질의하지 않는다).
     * @return 요청한 상태 중 <b>주문이 한 건이라도 있는</b> 상태만. 0 건인 상태는 행 자체가 없다
     */
    List<StatusWaiting> waitingByStatus(Map<String, LocalDateTime> deadlineByStatus);

    /**
     * 상태 한 칸의 대기 집계.
     *
     * @param status              주문 상태(DB 원문)
     * @param count               건수
     * @param oldestWaitingSince  가장 오래된 대기 시작 시각
     * @param overdueCount        기한 초과 건수
     * @param withoutHistoryCount 상태 변경 이력이 없어 주문 일시로 대신 잰 건수
     */
    record StatusWaiting(
            String status,
            long count,
            LocalDateTime oldestWaitingSince,
            long overdueCount,
            long withoutHistoryCount) {
    }
}
