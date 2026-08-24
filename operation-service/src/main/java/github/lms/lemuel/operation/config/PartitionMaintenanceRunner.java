package github.lms.lemuel.operation.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 + 매월 1회 월별 파티션 런웨이를 굴린다 — operation-service(opslab).
 *
 * <p>두 개의 파티션드 테이블을 유지한다: {@code audit_logs}(ensure_audit_log_partition) 와
 * {@code ops_metric_bucket}(ensure_ops_metric_bucket_partition). 월별 RANGE 파티션은 유한 구간(2028-12)까지만
 * 선생성돼 그 이후 삽입은 DEFAULT 파티션으로 흘러들어 프루닝·리텐션 이점이 사라진다. 이 러너가 부팅 때 두
 * 함수를 호출해 미래 파티션을 굴린다(재배포·재기동 주기로 런웨이가 계속 갱신됨).
 *
 * <p><b>prune(파기)은 호출하지 않는다</b> — 삭제는 운영 판단. 선생성만 자동화.
 * <b>fail-open:</b> 테스트 DB(create-drop)엔 함수가 없어 실패하므로 각 호출을 독립적으로 예외를 삼켜(warn)
 * 부팅을 막지 않는다(한 함수 실패가 다른 함수 호출을 막지 않도록 개별 try-catch).
 */
@Component
public class PartitionMaintenanceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final String schema;
    private final int monthsAhead;

    public PartitionMaintenanceRunner(
            JdbcTemplate jdbcTemplate,
            // 네이티브 SQL 은 hibernate.default_schema 를 무시하므로 함수명을 명시 한정한다(operation=opslab).
            @Value("${spring.jpa.properties.hibernate.default_schema:public}") String schema,
            @Value("${app.partition.months-ahead:3}") int monthsAhead) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
        this.monthsAhead = monthsAhead;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensurePartitions();
    }

    /**
     * 매월 1일 02:30 — 미래 파티션 롤(부팅 ApplicationRunner + 월간 스케줄 병행).
     *
     * <p>무재배포 장기 가동 시 부팅 1회만으로는 런웨이가 갱신되지 않아 파티션이 소진된다
     * (order/settlement 의 월간 @Scheduled 와 비대칭이던 갭).
     *
     * <p><b>락이 없는 이유 — 전제를 명시한다.</b> 원래 주석은 "단일 인스턴스 <b>위성 서비스</b>라
     * 노드 경합이 없어 안전하다"였다. operation 은 ADR 0041·0043 으로 알림·게시판·교육을 흡수한 코어라 그 표현은 더 이상 맞지 않는다.
     * 정확한 상태는 <b>이 모듈이 단일 레플리카 배포를 전제한다</b>는 것이다 — 근거는 배포 구성이
     * 아니라 코드에 있다: operation-service 에는 {@code shedlock} 테이블 마이그레이션이 없어
     * {@code @SchedulerLock} 을 붙일 수단 자체가 없다(락 프로바이더는 shared-common 에 있지만
     * 락 테이블은 모듈 DB 마다 따로 필요하다 — 실제로 order·settlement·finance 에만 있다).
     *
     * <p>따라서 스케일아웃하려면 <b>shedlock 마이그레이션부터</b> 넣어야 한다. 그 순서를 어기고
     * replicas 만 올리면 월 1회 02:30 에 파드 수만큼 동시 DDL 이 돌고, 아래 {@code ensurePartitions()}
     * 는 fail-open(warn 로그)이라 실패해도 조용하다. 이 전제는
     * {@code scheduler-lock-gate.test.mjs} 가 "락 테이블이 있는 모듈에서 락 없는 @Scheduled 금지"로
     * 기계 강제한다 — 이 모듈에 shedlock 테이블이 생기는 순간 이 클래스가 FAIL 로 드러난다.
     */
    @Scheduled(cron = "${app.partition.ensure-cron:0 30 2 1 * *}", zone = "Asia/Seoul")
    public void ensureMonthly() {
        ensurePartitions();
    }

    private void ensurePartitions() {
        ensure("ensure_audit_log_partition", monthsAhead);
        ensure("ensure_ops_metric_bucket_partition", monthsAhead);
    }

    // 동적 SQL 경고(java:S2077) 억제 — 이어 붙이는 건 식별자뿐이다: 스키마는 애플리케이션
    // 설정(hibernate.default_schema), 함수명은 코드 상수다. 요청에서 온 값은 이 경로에 닿지 않으며
    // 유일한 값 인자는 바인딩 파라미터(?)로 넘긴다. 네이티브 SQL 은 식별자를 바인딩할 수 없다.
    @SuppressWarnings("java:S2077")
    private void ensure(String function, int horizon) {
        try {
            Integer created = jdbcTemplate.queryForObject(
                    "SELECT " + schema + "." + function + "(?)", Integer.class, horizon);
            log.info("[PartitionMaintenance] {}({}) 완료: 신규 파티션 {}개", function, horizon, created);
        } catch (RuntimeException e) {
            log.warn("[PartitionMaintenance] {} 실패 — 유지보수 스킵 (fail-open): {}", function, e.getMessage());
        }
    }
}
