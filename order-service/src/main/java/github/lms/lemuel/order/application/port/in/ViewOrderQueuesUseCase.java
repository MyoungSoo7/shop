package github.lms.lemuel.order.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 상태별 작업 큐 — <b>운영자가 지금 손대야 하는 주문</b>을 상태 묶음별로 센다.
 *
 * <p>{@code GET /orders/admin/summary} 와 겹쳐 보이지만 묻는 것이 다르다. 요약은 "각 상태에 몇 건
 * 있는가"이고, 여기는 "그중 <b>얼마나 오래 방치됐는가</b>"다. 건수만으로는 취소 신청 12건이
 * 방금 들어온 12건인지 사흘째 아무도 안 본 12건인지 구분되지 않는다 — 화면상 두 경우는 같은
 * 숫자다.
 *
 * <p>종단 상태(CANCELED·REFUNDED·DELIVERED)는 큐에 없다. 할 일이 남지 않은 주문이라
 * 섞어 두면 "밀린 일" 숫자가 영업 규모를 따라 계속 커진다.
 */
public interface ViewOrderQueuesUseCase {

    OrderQueues view();

    /**
     * 큐 한 칸.
     *
     * @param key                  큐 식별자(화면·CSV 가 라벨 대신 이걸로 참조한다)
     * @param label                운영자용 한글 이름
     * @param statuses             이 큐가 포함하는 주문 상태
     * @param count                총 건수
     * @param oldestWaitingSince   가장 오래 기다린 주문이 <b>이 상태가 된</b> 시각. 비어 있으면 {@code null}
     * @param oldestWaitingHours   그 대기 시간(시간 단위). 비어 있으면 {@code null}
     * @param slaHours             이 큐의 처리 기한
     * @param overdueCount         기한을 넘긴 건수
     * @param ageFromOrderDateCount 상태 변경 이력이 없어 <b>주문 일시로 대신 잰</b> 건수.
     *                             {@link #oldestWaitingSince} 와 {@link #overdueCount} 가 그만큼
     *                             과장돼 있다는 뜻이라 화면에 같이 내보낸다 — 숨기면 이력이 없는
     *                             옛 주문이 영원히 "3년째 밀린 일"로 뜬다.
     */
    record QueueBucket(
            String key,
            String label,
            List<String> statuses,
            long count,
            LocalDateTime oldestWaitingSince,
            Long oldestWaitingHours,
            int slaHours,
            long overdueCount,
            long ageFromOrderDateCount) {
    }

    /**
     * @param asOf   집계 기준 시각(KST). 기한 초과 판정이 이 시각 기준이라 응답에 실어 보낸다
     * @param buckets 큐 목록. <b>건수가 0 인 큐도 남긴다</b> — 사라지면 "일이 없다"와
     *                "큐가 없어졌다"가 화면에서 같아진다
     */
    record OrderQueues(LocalDateTime asOf, List<QueueBucket> buckets) {

        /** 밀린 일 총합 — 큐를 다 더한 값. */
        public long totalCount() {
            return buckets.stream().mapToLong(QueueBucket::count).sum();
        }

        /** 기한 초과 총합. */
        public long totalOverdue() {
            return buckets.stream().mapToLong(QueueBucket::overdueCount).sum();
        }
    }
}
