package github.lms.lemuel.config;

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
class PartitionMaintenanceSchedulerTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Test
    void ensureMonthly_는_스키마_한정_ensure_함수에_위임한다() {
        var scheduler = new PartitionMaintenanceScheduler(jdbcTemplate, "opslab", 3);

        scheduler.ensureMonthly();

        verify(jdbcTemplate).queryForObject("SELECT opslab.ensure_audit_log_partition(?)", Integer.class, 3);
    }

    @Test
    void ensureOnStartup_도_동일_함수에_위임한다() {
        var scheduler = new PartitionMaintenanceScheduler(jdbcTemplate, "opslab", 6);

        scheduler.ensureOnStartup();

        verify(jdbcTemplate).queryForObject("SELECT opslab.ensure_audit_log_partition(?)", Integer.class, 6);
    }

    @Test
    void 함수가_없어_예외가_나도_삼켜서_부팅_스케줄을_막지_않는다() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenThrow(new RuntimeException("function ensure_audit_log_partition does not exist"));
        var scheduler = new PartitionMaintenanceScheduler(jdbcTemplate, "opslab", 3);

        assertDoesNotThrow(scheduler::ensureMonthly);
        assertDoesNotThrow(scheduler::ensureOnStartup);
    }
}
