package github.lms.lemuel.operation.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartitionMaintenanceRunnerTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Test
    void run_은_audit_와_ops_metric_bucket_두_함수에_모두_위임한다() {
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "opslab", 3);

        runner.run(null);

        verify(jdbcTemplate).queryForObject("SELECT opslab.ensure_audit_log_partition(?)", Integer.class, 3);
        verify(jdbcTemplate).queryForObject("SELECT opslab.ensure_ops_metric_bucket_partition(?)", Integer.class, 3);
    }

    @Test
    void 한_함수가_실패해도_삼키고_다른_함수를_계속_호출한다() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenThrow(new RuntimeException("function does not exist"));
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "opslab", 3);

        assertDoesNotThrow(() -> runner.run(null));
        // 개별 try-catch 라 첫 함수 실패가 두 번째 호출을 막지 않는다.
        verify(jdbcTemplate).queryForObject("SELECT opslab.ensure_audit_log_partition(?)", Integer.class, 3);
        verify(jdbcTemplate).queryForObject("SELECT opslab.ensure_ops_metric_bucket_partition(?)", Integer.class, 3);
    }

    @Test
    void ensureMonthly_도_audit_와_ops_metric_bucket_두_함수에_모두_위임한다() {
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "opslab", 3);

        runner.ensureMonthly();

        verify(jdbcTemplate).queryForObject("SELECT opslab.ensure_audit_log_partition(?)", Integer.class, 3);
        verify(jdbcTemplate).queryForObject("SELECT opslab.ensure_ops_metric_bucket_partition(?)", Integer.class, 3);
    }

    @Test
    void ensureMonthly_도_예외를_삼켜서_스케줄을_막지_않는다() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenThrow(new RuntimeException("function does not exist"));
        var runner = new PartitionMaintenanceRunner(jdbcTemplate, "opslab", 3);

        assertDoesNotThrow(runner::ensureMonthly);
    }
}
