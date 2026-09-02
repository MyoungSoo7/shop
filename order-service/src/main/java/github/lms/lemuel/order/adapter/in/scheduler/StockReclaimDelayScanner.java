package github.lms.lemuel.order.adapter.in.scheduler;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.application.port.in.BatchTargetDate;
import github.lms.lemuel.common.opssignal.OpsSignal;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.order.application.port.out.LoadPendingStockReclaimPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 회수 지연 스캐너.
 *
 * <p>배송 후 환불로 보류된 재고는 회수가 확인돼야 판매 가능 상태로 돌아온다. 회수가 오지 않으면
 * 운영자가 {@code /admin/stock-reclaim} 을 열어보기 전까지 아무도 모르고, 그동안 팔 수 있는 물건이
 * 묶인 채 남는다. 임계를 넘긴 건에 운영 신호를 쏴 operation-service 인시던트로 잡히게 한다.
 *
 * <p><b>임계를 갓 넘긴 구간만 훑는다</b>(배송 지연 스캐너와 동형) — 매 주기 전량을 훑으면 같은 건이
 * 계속 재발행돼 인시던트가 노이즈가 되고, 진짜 신규 지연이 묻힌다. 구간 폭 = 스캔 주기라 건당 대략 1회.
 *
 * <p>신호는 관측 목적이라 <b>실패해도 조용히 넘어간다</b> — 한 건의 발행 실패가 나머지 건이나
 * 스케줄러 스레드를 죽여선 안 된다.
 *
 * <p><b>창을 건너뛰면 그 창의 건은 영원히 신호를 못 받는다</b>(배송 지연 스캐너와 동형). 다음 주기는
 * 다음 창만 보기 때문에, 만료 배치들처럼 "다음에 다시 잡히는" 구조가 아니다. 그래서 창을
 * {@link #scanCrossings} 의 인자로 밖에 내고 놓친 날을
 * {@code POST /admin/batch-runs/stock-reclaim-delay-scan/rerun} 으로 되돌릴 수 있게 했다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class StockReclaimDelayScanner implements RerunnableBatch {

    private static final Logger log = LoggerFactory.getLogger(StockReclaimDelayScanner.class);

    /** 원장·재실행 API 의 키. */
    public static final String BATCH_NAME = "stock-reclaim-delay-scan";

    private final LoadPendingStockReclaimPort loadPort;
    private final OpsSignalPort opsSignalPort;
    private final BatchRunRecorder recorder;
    private final Duration threshold;
    private final Duration scanInterval;
    private final int batchLimit;

    public StockReclaimDelayScanner(
            LoadPendingStockReclaimPort loadPort,
            OpsSignalPort opsSignalPort,
            BatchRunRecorder recorder,
            @Value("${app.ops.stock-reclaim-delay.threshold:14d}") Duration threshold,
            // @Scheduled 와 같은 프로퍼티를 읽는다 — 둘로 나누면 한쪽만 바뀌어 구간과 주기가 어긋난다
            // (구간 폭 < 주기면 지연 건을 영영 놓치고, 크면 같은 건이 반복 발행된다).
            @Value("${app.ops.stock-reclaim-delay.scan-interval-ms:21600000}") Duration scanInterval,
            @Value("${app.ops.stock-reclaim-delay.batch-limit:200}") int batchLimit) {
        this.loadPort = loadPort;
        this.opsSignalPort = opsSignalPort;
        this.recorder = recorder;
        this.threshold = threshold;
        this.scanInterval = scanInterval;
        this.batchLimit = batchLimit;
    }

    // 락이 필요한 이유: 이 스캔은 임계 초과 건마다 운영 신호를 쏜다. 파드 N개가 같은 창을 동시에
    // 훑으면 같은 건에 신호가 N번 나가 인시던트 노이즈가 된다(order-service 는 shedlock 테이블을
    // 가진 다중 인스턴스 전제 모듈이다 — scheduler-lock-gate 가 강제).
    @Scheduled(fixedDelayString = "${app.ops.stock-reclaim-delay.scan-interval-ms:21600000}")
    @SchedulerLock(name = "stock-reclaim-delay-scan", lockAtMostFor = "PT30M")
    public void scan() {
        try {
            recorder.recordScheduledOutcome(BATCH_NAME, () -> {
                LocalDateTime crossedBefore = LocalDateTime.now().minus(threshold);
                return scanCrossings(crossedBefore.minus(scanInterval), crossedBefore);
            });
        } catch (RuntimeException e) {
            // 관측 경로가 스케줄러 스레드를 죽이면 이후 주기가 통째로 멈춘다.
            log.warn("회수 지연 스캔 실패 — 다음 주기에 재시도한다", e);
        }
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public String description() {
        return "회수 지연 스캔 — 재고 회수가 임계를 넘긴 주문에 운영 관제 신호를 발행한다";
    }

    /** 발행 자체가 부수효과라 "안 쏘고 세보기" 경로가 없다 — 지원하는 척하지 않는다. */
    @Override
    public boolean supportsDryRun() {
        return false;
    }

    /**
     * 놓친 창 복구. {@code targetDate} 는 <b>회수 대기에 들어간 날</b>이다 — 지연 판정이 그 시각
     * 기준이므로 그 날 하루분을 통째로 다시 훑는다.
     */
    @Override
    public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
        return scanCrossings(BatchTargetDate.startOf(targetDate), BatchTargetDate.startOfNextDay(targetDate));
    }

    /**
     * {@code (crossedAfter, crossedBefore]} 창의 회수 지연 건에 신호를 발행한다.
     *
     * <p>발행에 실패한 건이 있으면 <b>부분 실패</b>로 돌려준다 — 전부 실패했는데 원장에 SUCCEEDED 로
     * 남으면 "신호가 안 왔다" 를 조사할 단서가 사라진다.
     */
    private BatchRunOutcome scanCrossings(LocalDateTime crossedAfter, LocalDateTime crossedBefore) {
        List<Order> delayed = loadPort
                .findStockReclaimCrossedBetween(crossedAfter, crossedBefore, batchLimit)
                .stream()
                // 쿼리 조건이 도메인 규칙과 어긋나도 잘못된 건에 신호를 쏘지 않는다.
                .filter(Order::isAwaitingStockReclaim)
                .toList();
        if (delayed.isEmpty()) {
            return BatchRunOutcome.succeeded(0);
        }
        int emitted = 0;
        for (Order order : delayed) {
            if (emit(order)) {
                emitted++;
            }
        }
        log.info("회수 지연 신호 발행: {} 건 (임계 {}일 초과, 창 {} ~ {})",
                emitted, threshold.toDays(), crossedAfter, crossedBefore);
        int dropped = delayed.size() - emitted;
        if (dropped > 0) {
            return BatchRunOutcome.partiallyFailed(emitted,
                    "신호 발행 실패: dropped=" + dropped + ", emitted=" + emitted);
        }
        return BatchRunOutcome.succeeded(emitted);
    }

    private boolean emit(Order order) {
        try {
            opsSignalPort.emit(new OpsSignal(
                    OpsSignalCategory.STOCK_RECLAIM_DELAYED, "order-service", "order",
                    String.valueOf(order.getId()), OpsSignal.SEVERITY_WARNING, Instant.now(),
                    Map.of("quantity", order.getItems().stream().mapToInt(OrderItem::getQuantity).sum(),
                            "thresholdDays", threshold.toDays())));
            return true;
        } catch (RuntimeException e) {
            log.warn("회수 지연 신호 발행 실패 — 스킵: orderId={}", order.getId(), e);
            return false;
        }
    }
}
