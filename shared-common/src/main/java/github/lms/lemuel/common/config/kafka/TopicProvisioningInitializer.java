package github.lms.lemuel.common.config.kafka;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 기동 시 이 모듈이 <b>소유한</b> 토픽을 프로비저닝한다 (ADR 0035).
 *
 * <p>리스너 컨테이너가 뜨기 전에 실행되도록 {@link SmartInitializingSingleton} 을 쓴다 — 컨슈머가
 * 없는 토픽을 만나 {@code missing-topics-fatal:false} 로 조용히 기다리는 상태를 줄인다.
 *
 * <p>브로커에 닿지 못해도 기동을 막지 않는다. 토픽 프로비저닝은 편의 기능이고, 이것 때문에 서비스가
 * 못 뜨면 장애 반경이 더 커진다. 대신 실패는 ERROR 로 남기고 드리프트는 게이지로 노출한다.
 */
public class TopicProvisioningInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TopicProvisioningInitializer.class);

    /**
     * 카탈로그 선언과 브로커 실제 값이 어긋난 항목 수(파티션·복제본). 0 이 아니면 순서 보장이나
     * 내구성 전제가 흔들린 상태다. 보존기간은 자동으로 맞추므로 여기 잡히지 않는다.
     */
    static final String DRIFT_GAUGE = "kafka.topic.partition.drift";

    private final TopicCatalog catalog;
    private final TopicProvisioner provisioner;
    private final String module;
    private final AtomicInteger driftCount = new AtomicInteger();

    public TopicProvisioningInitializer(TopicCatalog catalog, TopicProvisioner provisioner,
                                        String module, MeterRegistry meterRegistry) {
        this.catalog = catalog;
        this.provisioner = provisioner;
        this.module = module;
        Gauge.builder(DRIFT_GAUGE, driftCount, AtomicInteger::get)
                .description("카탈로그 선언과 브로커 값이 어긋난 항목 수 — 파티션·복제본 (ADR 0035)")
                .register(meterRegistry);
    }

    @Override
    public void afterSingletonsInstantiated() {
        provisionQuietly();
    }

    /** @return 드리프트 건수. 예외는 삼키고 -1 을 돌려준다(기동을 막지 않는다). */
    int provisionQuietly() {
        if (module == null || module.isBlank()) {
            log.debug("app.kafka.topic.owner 가 없다 — 컨슈머 전용 모듈로 보고 토픽 프로비저닝을 건너뛴다");
            return 0;
        }
        try {
            TopicProvisioner.Report report = provisioner.provision(catalog, module);
            if (!report.created().isEmpty()) {
                log.info("Kafka 토픽 프로비저닝 완료: module={}, created={}", module, report.created());
            }
            if (!report.retentionAligned().isEmpty()) {
                // 보존기간은 되돌릴 수 있고 키·순서와 무관하므로 자동으로 맞춘다(파티션과의 결정적 차이).
                log.info("Kafka 토픽 보존기간을 카탈로그에 맞춰 고정: module={}, topics={}",
                        module, report.retentionAligned());
            }
            for (TopicProvisioner.Drift drift : report.drifted()) {
                // 고치지 않는다 — 파티션 변경은 키 재해시라 순서 보장을 소급해서 깬다. 사람이 판단한다.
                log.warn("Kafka 토픽 드리프트: topic={}, {} 카탈로그={} 브로커={} — "
                                + "브로커를 맞출지(rpk) 카탈로그를 고칠지는 도메인 판단이다 (ADR 0035)",
                        drift.topic(), drift.property(), drift.declared(), drift.actual());
            }
            driftCount.set(report.drifted().size());
            return report.drifted().size();
        } catch (RuntimeException e) {
            log.error("Kafka 토픽 프로비저닝 실패 (기동은 계속한다): module={}", module, e);
            return -1;
        }
    }
}
