package github.lms.lemuel.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * audit_logs 월별 파티션 런웨이 자동 유지 — order-service(opslab).
 *
 * <p>월별 RANGE 파티션은 마이그레이션이 2028-12 까지만 선생성해 뒀고(일회성 런웨이), 그 이후 삽입은
 * DEFAULT 파티션으로 흘러들어 프루닝·리텐션 이점이 사라진다. 이 스케줄러가 {@code ensure_audit_log_partition}
 * 을 <b>부팅 시 1회 + 매월 1회</b> 호출해 미래 파티션을 굴려 런웨이 소진을 막는다.
 *
 * <p><b>prune(파기)은 자동 호출하지 않는다.</b> 금융 감사 로그는 장기 보존이 원칙이라 파티션 삭제
 * ({@code prune_audit_logs})는 운영 판단에 위임한다 — 여기서는 선생성(ensure)만 자동화한다.
 *
 * <p><b>fail-open:</b> 테스트 DB(create-drop)엔 유지보수 함수가 없어 호출이 실패한다. 유지보수는
 * 보조 작업이므로 예외를 삼켜(warn 로그) 부팅·스케줄이 절대 실패로 이어지지 않게 한다.
 */
@Component
public class PartitionMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final String schema;
    private final int monthsAhead;

    public PartitionMaintenanceScheduler(
            JdbcTemplate jdbcTemplate,
            // 네이티브 SQL 은 hibernate.default_schema 를 무시하므로 함수명을 명시 한정한다(order=opslab).
            @Value("${spring.jpa.properties.hibernate.default_schema:public}") String schema,
            @Value("${app.partition.months-ahead:3}") int monthsAhead) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
        this.monthsAhead = monthsAhead;
    }

    /** 부팅 시 1회 — 새로 뜬 노드가 자기 파티션 런웨이를 자가 치유. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureOnStartup() {
        ensureAuditPartitions();
    }

    /** 매월 1일 02:30 — 미래 파티션 롤. replicas 중 1 개만(ShedLock). */
    @Scheduled(cron = "${app.partition.ensure-cron:0 30 2 1 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-partition-ensure-monthly", lockAtMostFor = "PT10M")
    public void ensureMonthly() {
        ensureAuditPartitions();
    }

    // 동적 SQL 경고(java:S2077) 억제 — 이어 붙이는 건 식별자뿐이다: 스키마는 애플리케이션
    // 설정(hibernate.default_schema), 함수명은 코드 상수다. 요청에서 온 값은 이 경로에 닿지 않으며
    // 유일한 값 인자는 바인딩 파라미터(?)로 넘긴다. 네이티브 SQL 은 식별자를 바인딩할 수 없다.
    @SuppressWarnings("java:S2077")
    private void ensureAuditPartitions() {
        try {
            Integer created = jdbcTemplate.queryForObject(
                    "SELECT " + schema + ".ensure_audit_log_partition(?)", Integer.class, monthsAhead);
            log.info("[PartitionMaintenance] ensure_audit_log_partition({}) 완료: 신규 파티션 {}개", monthsAhead, created);
        } catch (RuntimeException e) {
            log.warn("[PartitionMaintenance] ensure_audit_log_partition 실패 — 유지보수 스킵 (fail-open): {}", e.getMessage());
        }
    }
}
