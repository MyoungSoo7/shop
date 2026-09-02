package github.lms.lemuel.order.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 상태 이력 조회 — "이 주문이 왜 지금 이 상태인가" 에 답한다.
 *
 * <p>{@code order_status_history} 는 오래전부터 모든 전이를 적어 왔지만 <b>읽는 길이 없었다.</b>
 * 그래서 CS 문의가 오면 운영 DB 에 직접 붙어서 조회해야 했고, 그건 (1) DB 접근 권한을 CS 에게 주거나
 * (2) 개발자가 매번 대신 조회하거나 둘 중 하나를 뜻했다. 둘 다 나쁜 선택지다.
 *
 * <p>단순히 행을 그대로 뱉지 않고 두 가지를 얹는다. 이 둘이 이 화면을 "테이블 덤프" 와 가른다:
 * <ul>
 *   <li><b>체류 시간</b> — 각 상태에 얼마나 머물렀는지. "배송준비에서 9일 멈춰 있음" 은 행 목록을
 *       눈으로 빼서는 잘 안 보이지만, 숫자로 나오면 바로 보인다.</li>
 *   <li><b>주문 상태와의 대조</b> — 마지막 이력의 도착 상태가 주문의 현재 상태와 다르면 <b>어딘가에서
 *       이력을 안 남기고 상태를 바꾼 경로가 있다</b>는 뜻이다. 이건 이력 화면이 아니면 영영 안 보인다.</li>
 * </ul>
 */
public interface ViewOrderStatusHistoryUseCase {

    /**
     * 이 주문의 상태 변경 타임라인.
     *
     * @param now 마지막(현재) 상태의 체류 시간 계산 기준. 호출자가 넘겨야 테스트가 시계에 안 흔들린다
     * @throws github.lms.lemuel.order.domain.exception.OrderNotFoundException 주문이 없으면.
     *         빈 목록으로 답하면 "없는 주문" 과 "이력이 안 남은 주문" 이 구분되지 않는다 —
     *         후자는 조사할 버그이고 전자는 오타다
     */
    OrderStatusTimeline view(Long orderId, LocalDateTime now);

    /**
     * @param currentStatus        주문 애그리거트가 들고 있는 지금 상태
     * @param lastRecordedStatus   이력의 마지막 도착 상태. 이력이 하나도 없으면 {@code null}
     * @param historyMatchesOrder  위 둘이 같은지. {@code false} 면 이력을 안 남긴 전이가 있다는 신호
     */
    record OrderStatusTimeline(Long orderId,
                               String currentStatus,
                               String lastRecordedStatus,
                               boolean historyMatchesOrder,
                               List<StatusStep> steps) {
    }

    /**
     * 타임라인의 한 칸.
     *
     * @param dwellSeconds 이 상태에 머문 시간(초). 마지막 칸은 {@code now} 까지의 경과 —
     *                     즉 <b>지금 몇 초째 여기 있는지</b>다
     */
    record StatusStep(Long id,
                      String previousStatus,
                      String newStatus,
                      String changedBy,
                      String reason,
                      LocalDateTime changedAt,
                      long dwellSeconds) {
    }
}
