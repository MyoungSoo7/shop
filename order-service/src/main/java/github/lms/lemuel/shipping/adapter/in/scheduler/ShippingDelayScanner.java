package github.lms.lemuel.shipping.adapter.in.scheduler;

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
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ShippingDelayScanner {

    private static final Logger log = LoggerFactory.getLogger(ShippingDelayScanner.class);

    private final SpringDataShipmentRepository repository;
    private final OpsSignalPort opsSignalPort;
    private final long thresholdHours;
    private final long scanIntervalMs;

    public ShippingDelayScanner(SpringDataShipmentRepository repository,
                                OpsSignalPort opsSignalPort,
                                @Value("${app.ops.shipping-delay.threshold-hours:72}") long thresholdHours,
                                @Value("${app.ops.shipping-delay.scan-interval-ms:21600000}") long scanIntervalMs) {
        this.repository = repository;
        this.opsSignalPort = opsSignalPort;
        this.thresholdHours = thresholdHours;
        this.scanIntervalMs = scanIntervalMs;
    }

    /** 기본 6시간(21_600_000ms)마다 스캔. crossing window = 스캔 주기와 동일. */
    // 락이 필요한 이유: 위 javadoc 의 "crossing window" 는 **시간창** 중복(같은 건이 매 스캔마다 재발행)
    // 을 막는 장치지 **인스턴스** 중복을 막지 못한다. 두 파드가 같은 창을 동시에 스캔하면 같은 배송에
    // 신호가 두 번 나간다 — 축이 다른 방어라 둘 다 필요하다.
    @Scheduled(fixedDelayString = "${app.ops.shipping-delay.scan-interval-ms:21600000}")
    @SchedulerLock(name = "shipping-delay-scan", lockAtMostFor = "PT30M")
    @Transactional(readOnly = true)
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime crossedBefore = now.minusHours(thresholdHours);
        LocalDateTime crossedAfter = crossedBefore.minus(Duration.ofMillis(scanIntervalMs));

        List<ShipmentJpaEntity> delayed =
                repository.findNewlyDelayed(ShippingStatus.IN_TRANSIT, crossedBefore, crossedAfter);
        if (delayed.isEmpty()) {
            return;
        }
        for (ShipmentJpaEntity shipment : delayed) {
            opsSignalPort.emit(new OpsSignal(
                    OpsSignalCategory.SHIPPING_DELAYED, "order-service", "shipment",
                    String.valueOf(shipment.getId()), OpsSignal.SEVERITY_WARNING,
                    java.time.Instant.now(),
                    Map.of("orderId", shipment.getOrderId(), "thresholdHours", thresholdHours)));
        }
        log.info("배송 지연 신호 발행: {} 건 (임계 {}h 초과)", delayed.size(), thresholdHours);
    }
}
