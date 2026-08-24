package github.lms.lemuel.order.application.service;

import github.lms.lemuel.config.TimeConfig;
import github.lms.lemuel.order.application.port.in.GetPendingStockReclaimUseCase;
import github.lms.lemuel.order.application.port.out.LoadPendingStockReclaimPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 회수 대기 재고 조회 서비스.
 *
 * <p>어댑터가 인덱스로 좁혀 온 후보를 <b>도메인 판정으로 한 번 더 거른다</b>
 * ({@link Order#isAwaitingStockReclaim()}) — 쿼리 조건이 도메인 규칙과 어긋나도 잘못된 건이
 * 운영 화면에 오르지 않는다. 회수가 지연될수록 손실이 커지므로 오래 묶인 순으로 정렬한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetPendingStockReclaimService implements GetPendingStockReclaimUseCase {

    private final LoadPendingStockReclaimPort loadPort;

    @Override
    public List<PendingReclaim> findPending(LocalDateTime now, int limit) {
        return loadPort.findAwaitingStockReclaim(limit).stream()
                .filter(Order::isAwaitingStockReclaim)
                .map(order -> toPending(order, now))
                .sorted(Comparator.comparingLong(PendingReclaim::pendingDays).reversed()
                        .thenComparing(PendingReclaim::orderId))
                .toList();
    }

    private PendingReclaim toPending(Order order, LocalDateTime now) {
        LocalDateTime terminalAt = order.getUpdatedAt();
        return new PendingReclaim(
                order.getId(),
                order.getUserId(),
                order.getStatus().name(),
                terminalAt,
                pendingDays(terminalAt, now),
                order.getItems().stream().mapToInt(OrderItem::getQuantity).sum(),
                order.getAmount(),
                order.getItems().stream().map(GetPendingStockReclaimService::toLine).toList());
    }

    /**
     * 종단 이후 경과일. 시계 오차로 종단 시각이 미래여도 음수를 내지 않는다.
     *
     * <p>두 시각 모두 커머스 업무 표준시(KST)로 기록·조회되므로, 경과 시간은 그 시간대에서 센다.
     * 시간대 없는 {@code LocalDateTime} 끼리의 뺄셈은 "하루 = 24시간"을 암묵 가정한다 — KST 는
     * 서머타임이 없어 결과는 같지만, 어느 시간대의 경과인지를 타입에 남긴다.
     */
    private static long pendingDays(LocalDateTime terminalAt, LocalDateTime now) {
        if (terminalAt == null || now == null) {
            return 0L;
        }
        long days = Duration.between(terminalAt.atZone(TimeConfig.KST), now.atZone(TimeConfig.KST)).toDays();
        return Math.max(days, 0L);
    }

    private static PendingLine toLine(OrderItem item) {
        return new PendingLine(item.getProductId(), item.getVariantId(),
                item.getSku(), item.getProductName(), item.getQuantity());
    }
}
