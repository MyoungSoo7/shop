package github.lms.lemuel.config;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.application.port.in.BatchTargetDate;
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
 *
 * <h2>삼킨 실패를 원장에는 남긴다</h2>
 * 이 배치의 실패는 <b>즉시 아무 증상이 없다</b>. 런웨이가 남아 있는 동안은 DEFAULT 파티션으로
 * 흘러들 일도 없어, 매달 조용히 실패해도 2028-12 이후 어느 날에야 드러난다. 예외를 삼키는 설계는
 * 그대로 두되(부팅을 막으면 안 된다) <b>삼킨 사실은 {@code batch_run_history} 에 FAILED 로 적는다</b>
 * — 삼키는 것과 없던 일로 만드는 것은 다르다.
 *
 * <p>재실행 API 는 두지 않았다. 이 작업은 날짜분이 아니라 "지금부터 N개월" 을 채우는 것이라
 * 대상일 개념이 없고, 다시 돌리고 싶으면 파드를 재기동하면 부팅 경로가 같은 일을 한다.
 */
@Component
public class PartitionMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceScheduler.class);

    /** 원장 키. 재실행 대상은 아니다. */
    public static final String BATCH_NAME = "audit-partition-ensure";

    /** 부팅 자가치유 경로 — 스케줄 실행과 구분해 원장에 남긴다(파드 재기동마다 한 행). */
    private static final String TRIGGERED_BY_STARTUP = "startup";

    private final JdbcTemplate jdbcTemplate;
    private final BatchRunRecorder recorder;
    private final String schema;
    private final int monthsAhead;

    public PartitionMaintenanceScheduler(
            JdbcTemplate jdbcTemplate,
            BatchRunRecorder recorder,
            // 네이티브 SQL 은 hibernate.default_schema 를 무시하므로 함수명을 명시 한정한다(order=opslab).
            @Value("${spring.jpa.properties.hibernate.default_schema:public}") String schema,
            @Value("${app.partition.months-ahead:3}") int monthsAhead) {
        this.jdbcTemplate = jdbcTemplate;
        this.recorder = recorder;
        this.schema = schema;
        this.monthsAhead = monthsAhead;
    }

    /** 부팅 시 1회 — 새로 뜬 노드가 자기 파티션 런웨이를 자가 치유. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureOnStartup() {
        record(TRIGGERED_BY_STARTUP);
    }

    /** 매월 1일 02:30 — 미래 파티션 롤. replicas 중 1 개만(ShedLock). */
    @Scheduled(cron = "${app.partition.ensure-cron:0 30 2 1 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-partition-ensure-monthly", lockAtMostFor = "PT10M")
    public void ensureMonthly() {
        record(BatchRunRecorder.TRIGGERED_BY_SCHEDULER);
    }

    private void record(String triggeredBy) {
        recorder.recordOutcome(BATCH_NAME, BatchTargetDate.today(), triggeredBy, this::ensureAuditPartitions);
    }

    // 동적 SQL 경고(java:S2077) 억제 — 이어 붙이는 건 식별자뿐이다: 스키마는 애플리케이션
    // 설정(hibernate.default_schema), 함수명은 코드 상수다. 요청에서 온 값은 이 경로에 닿지 않으며
    // 유일한 값 인자는 바인딩 파라미터(?)로 넘긴다. 네이티브 SQL 은 식별자를 바인딩할 수 없다.
    @SuppressWarnings("java:S2077")
    private BatchRunOutcome ensureAuditPartitions() {
        try {
            Integer created = jdbcTemplate.queryForObject(
                    "SELECT " + schema + ".ensure_audit_log_partition(?)", Integer.class, monthsAhead);
            log.info("[PartitionMaintenance] ensure_audit_log_partition({}) 완료: 신규 파티션 {}개", monthsAhead, created);
            return BatchRunOutcome.succeeded(created == null ? 0 : created);
        } catch (RuntimeException e) {
            log.warn("[PartitionMaintenance] ensure_audit_log_partition 실패 — 유지보수 스킵 (fail-open): {}", e.getMessage());
            // 예외는 여기서 끝난다(부팅을 막지 않는다). 다만 삼켰다는 사실을 원장에 FAILED 로 남긴다.
            return BatchRunOutcome.partiallyFailed(0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
