package github.lms.lemuel.shipping.adapter.in.scheduler;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.application.port.in.BatchTargetDate;
import github.lms.lemuel.common.opssignal.OpsSignal;
import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.shipping.adapter.out.persistence.ShipmentJpaEntity;
import github.lms.lemuel.shipping.adapter.out.persistence.SpringDataShipmentRepository;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 배송 지연 스캐너 — IN_TRANSIT 상태로 임계(기본 72h)를 넘긴 배송을 주기적으로 찾아
 * 운영 관제 {@code shipping.delayed} 신호를 발행한다 (Phase 2b 채널 A, 배치 소스).
 *
 * <p>{@code app.kafka.enabled=true} 일 때만 동작(신호는 Kafka 로만 의미가 있음). 발행은
 * best-effort(OpsSignalPort 가 절대 throw 안 함)라 스캔이 비즈니스에 영향을 주지 않는다.
 *
 * <p>중복 발행 방지: "지연 임계를 이번 스캔 창에서 막 넘어선" 배송만 잡아(crossing window)
 * 같은 지연 건이 매 스캔마다 재발행되지 않게 한다 — 배송당 대략 1회.
 *
 * <p><b>이 배치는 창을 건너뛰면 그 창의 배송이 영원히 신호를 못 받는다.</b> 다음 주기는 다음 창만
 * 보기 때문이다 — 다른 만료 배치들처럼 "다음에 다시 잡히는" 구조가 아니다. 그래서 스캔 창을
 * 시계에서 뽑는 대신 {@link #scanCrossings} 의 인자로 밖에 내고, 재실행 경로가 놓친 날의
 * 창을 그대로 다시 넣을 수 있게 했다
 * ({@code POST /admin/batch-runs/shipping-delay-scan/rerun}).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ShippingDelayScanner implements RerunnableBatch {

    private static final Logger log = LoggerFactory.getLogger(ShippingDelayScanner.class);

    /** 원장·재실행 API 의 키. ShedLock 이름과 같게 두어 로그에서 짝을 찾기 쉽게 한다. */
    public static final String BATCH_NAME = "shipping-delay-scan";

    private final SpringDataShipmentRepository repository;
    private final OpsSignalPort opsSignalPort;
    private final BatchRunRecorder recorder;
    private final long thresholdHours;
    private final long scanIntervalMs;

    public ShippingDelayScanner(SpringDataShipmentRepository repository,
                                OpsSignalPort opsSignalPort,
                                BatchRunRecorder recorder,
                                @Value("${app.ops.shipping-delay.threshold-hours:72}") long thresholdHours,
                                @Value("${app.ops.shipping-delay.scan-interval-ms:21600000}") long scanIntervalMs) {
        this.repository = repository;
        this.opsSignalPort = opsSignalPort;
        this.recorder = recorder;
        this.thresholdHours = thresholdHours;
        this.scanIntervalMs = scanIntervalMs;
    }

    /** 기본 6시간(21_600_000ms)마다 스캔. crossing window = 스캔 주기와 동일. */
    // 락이 필요한 이유: 위 javadoc 의 "crossing window" 는 **시간창** 중복(같은 건이 매 스캔마다 재발행)
    // 을 막는 장치지 **인스턴스** 중복을 막지 못한다. 두 파드가 같은 창을 동시에 스캔하면 같은 배송에
    // 신호가 두 번 나간다 — 축이 다른 방어라 둘 다 필요하다.
    @Scheduled(fixedDelayString = "${app.ops.shipping-delay.scan-interval-ms:21600000}")
    @SchedulerLock(name = "shipping-delay-scan", lockAtMostFor = "PT30M")
    // 트랜잭션은 이 **진입점**에 붙는다 — 안쪽 scanCrossings 로 옮기면 self-invocation 이라 프록시를
    // 안 타고 조용히 무효가 된다. 원장 기록은 REQUIRES_NEW 라 이 readOnly 트랜잭션을 잠시 밀어낸다.
    @Transactional(readOnly = true)
    public void scan() {
        try {
            recorder.recordScheduled(BATCH_NAME, () -> {
                LocalDateTime crossedBefore = LocalDateTime.now().minusHours(thresholdHours);
                LocalDateTime crossedAfter = crossedBefore.minus(Duration.ofMillis(scanIntervalMs));
                return scanCrossings(crossedAfter, crossedBefore);
            });
        } catch (RuntimeException exception) {
            log.error("배송 지연 스캔 실패 — 다음 주기에 재시도한다", exception);
        }
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public String description() {
        return "배송 지연 스캔 — 지연 임계를 넘어선 배송에 운영 관제 신호를 발행한다";
    }

    /**
     * 이 배치는 <b>dry-run 을 지원하지 않는다</b> — 발행 자체가 부수효과라 "안 보내고 세보기" 를
     * 하려면 별도 경로가 필요하다. 지원하는 척하고 실제로 신호를 쏘는 것보다 거절하는 편이 낫다.
     */
    @Override
    public boolean supportsDryRun() {
        return false;
    }

    /**
     * 놓친 창 복구. {@code targetDate} 는 <b>임계를 넘어선 날</b>이 아니라 <b>발송된 날</b>이다 —
     * 지연 판정은 {@code shippedAt} 기준이므로, 그 날 발송분 전체를 하루 창으로 다시 훑는다.
     */
    @Override
    @Transactional(readOnly = true)
    public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
        return BatchRunOutcome.succeeded(scanCrossings(
                BatchTargetDate.startOf(targetDate), BatchTargetDate.startOfNextDay(targetDate)));
    }

    /**
     * {@code (crossedAfter, crossedBefore]} 창에서 지연 임계를 넘어선 IN_TRANSIT 배송에 신호를 발행한다.
     *
     * <p>창을 인자로 받는 이유는 스케줄 경로와 재실행 경로가 <b>같은 코드</b>를 타되 창만 달리 잡게
     * 하기 위해서다. 시계를 안에서 읽으면 놓친 창은 되돌릴 방법이 없다.
     */
    int scanCrossings(LocalDateTime crossedAfter, LocalDateTime crossedBefore) {
        List<ShipmentJpaEntity> delayed =
                repository.findNewlyDelayed(ShippingStatus.IN_TRANSIT, crossedBefore, crossedAfter);
        if (delayed.isEmpty()) {
            return 0;
        }
        for (ShipmentJpaEntity shipment : delayed) {
            opsSignalPort.emit(new OpsSignal(
                    OpsSignalCategory.SHIPPING_DELAYED, "order-service", "shipment",
                    String.valueOf(shipment.getId()), OpsSignal.SEVERITY_WARNING,
                    java.time.Instant.now(),
                    Map.of("orderId", shipment.getOrderId(), "thresholdHours", thresholdHours)));
        }
        log.info("배송 지연 신호 발행: {} 건 (임계 {}h 초과, 창 {} ~ {})",
                delayed.size(), thresholdHours, crossedAfter, crossedBefore);
        return delayed.size();
    }
}
