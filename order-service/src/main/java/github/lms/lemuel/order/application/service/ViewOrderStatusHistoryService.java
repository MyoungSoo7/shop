package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ViewOrderStatusHistoryUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.LoadOrderStatusHistoryPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatusChange;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ViewOrderStatusHistoryService implements ViewOrderStatusHistoryUseCase {

    private final LoadOrderPort loadOrderPort;
    private final LoadOrderStatusHistoryPort loadHistoryPort;

    public ViewOrderStatusHistoryService(LoadOrderPort loadOrderPort,
                                         LoadOrderStatusHistoryPort loadHistoryPort) {
        this.loadOrderPort = loadOrderPort;
        this.loadHistoryPort = loadHistoryPort;
    }

    @Override
    public OrderStatusTimeline view(Long orderId, LocalDateTime now) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<OrderStatusChange> changes = loadHistoryPort.findHistory(orderId);
        String currentStatus = order.getStatus() == null ? null : order.getStatus().name();
        String lastRecorded = changes.isEmpty() ? null : changes.get(changes.size() - 1).newStatus();

        return new OrderStatusTimeline(orderId,
                currentStatus,
                lastRecorded,
                // 이력이 아예 없으면 "일치" 라고 말하지 않는다. 결제된 주문에 이력이 0건이면 그 자체가
                // 조사할 신호고, 여기서 true 로 덮으면 화면에서 영영 안 보인다.
                lastRecorded != null && lastRecorded.equals(currentStatus),
                toSteps(changes, now));
    }

    /**
     * 각 칸의 체류 시간을 <b>다음 칸의 시각</b>으로 채운다. 마지막 칸만 {@code now} 를 쓴다.
     *
     * <p>음수는 0 으로 눌러 둔다. {@code changedAt} 이 {@code LocalDateTime.now()} 로 찍히는 값이라
     * 같은 트랜잭션 안의 두 전이가 뒤집혀 보일 수 있고(정렬은 id 로 하므로 순서 자체는 맞다),
     * 음수 체류 시간을 그대로 내보내면 화면이 그 주문을 이상한 주문으로 만든다. 순서는 id 가 진실이다.
     */
    private static List<StatusStep> toSteps(List<OrderStatusChange> changes, LocalDateTime now) {
        List<StatusStep> steps = new ArrayList<>(changes.size());
        for (int i = 0; i < changes.size(); i++) {
            OrderStatusChange change = changes.get(i);
            LocalDateTime until = (i + 1 < changes.size()) ? changes.get(i + 1).changedAt() : now;
            steps.add(new StatusStep(change.id(),
                    change.previousStatus(),
                    change.newStatus(),
                    change.changedBy(),
                    change.reason(),
                    change.changedAt(),
                    dwellSeconds(change.changedAt(), until)));
        }
        return steps;
    }

    private static long dwellSeconds(LocalDateTime from, LocalDateTime until) {
        if (from == null || until == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(from, until).toSeconds());
    }
}
