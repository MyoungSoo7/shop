package github.lms.lemuel.operation.audit.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogExport;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogPage;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;
import github.lms.lemuel.common.audit.domain.AuditAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 감사 로그 <b>반출</b>이 감사에 남는다는 계약.
 *
 * <p>바꾸는 조작은 전부 감사에 걸려 있었는데, 그 기록을 통째로 CSV 로 내려받는 조작만 아무 흔적도
 * 남기지 않았다. 파일에는 다른 운영자의 이메일·접속 IP·조작 상세가 그대로 들어간다. 사고 후
 * "누가 이 기간 감사 기록을 받아 갔는가"에 답할 수 없다는 뜻이었다.
 *
 * <p>애스펙트의 동작 자체는 shared-common 이 검증하므로, 여기서 고정하는 것은 <b>가로챌 자리가
 * 실제로 존재하는가</b>다 — 애노테이션이 사라지거나 다른 액션으로 바뀌면 감사는 조용히 멈춘다.
 */
class OperationAuditLogExportServiceTest {

    /** 반출이 조회 유스케이스로 그대로 위임되는지 보기 위한 최소 fake. */
    private static class RecordingSearch implements SearchAuditLogsUseCase {
        private final AtomicReference<AuditLogQuery> seen = new AtomicReference<>();
        private final AuditLogExport result =
                new AuditLogExport(List.of(), true, 1234L);

        @Override
        public AuditLogPage search(AuditLogQuery query) {
            throw new UnsupportedOperationException("반출 경로는 search 를 쓰지 않는다");
        }

        @Override
        public List<AuditActionCount> countByAction(AuditLogQuery query) {
            throw new UnsupportedOperationException("반출 경로는 countByAction 을 쓰지 않는다");
        }

        @Override
        public AuditLogExport export(AuditLogQuery query) {
            seen.set(query);
            return result;
        }
    }

    private static Method exportMethod() throws NoSuchMethodException {
        return OperationAuditLogExportService.class.getMethod("export", AuditLogQuery.class);
    }

    @Test
    @DisplayName("반출은 감사 애스펙트가 가로챌 수 있는 자리에 있다")
    void theExportIsAnnotatedSoTheAuditAspectCanInterceptIt() throws Exception {
        Auditable auditable = exportMethod().getAnnotation(Auditable.class);

        assertThat(auditable).as("애노테이션이 사라지면 반출은 아무 흔적도 남기지 않는다").isNotNull();
        assertThat(auditable.action()).isEqualTo(AuditAction.OPERATION_AUDIT_LOG_EXPORTED);
        assertThat(auditable.resourceType()).isEqualTo("AuditLog");
    }

    @Test
    @DisplayName("상세에 조회 조건과 반출 규모가 함께 남는다")
    void theDetailCarriesBothTheQueryAndTheSizeOfWhatLeft() throws Exception {
        String detail = exportMethod().getAnnotation(Auditable.class).detail();

        // 조건이 없으면 무엇이 나갔는지 복원할 수 없고, 규모가 없으면 상한에 걸려 잘린 반출과
        // 전량 반출을 구분하지 못해 피해 산정이 최악값으로 고정된다.
        assertThat(detail)
                .contains("actorEmail").contains("action").contains("from").contains("to")
                .contains("exportedRows").contains("matchedTotal").contains("truncated");
    }

    @Test
    @DisplayName("실패한 반출 시도도 기록된다")
    void failedExportAttemptsAreRecordedToo() throws Exception {
        // 권한이 없거나 질의가 터진 반출 '시도' 는 성공한 반출만큼이나 알아야 하는 사건이다.
        assertThat(exportMethod().getAnnotation(Auditable.class).recordOnFailure()).isTrue();
    }

    @Test
    @DisplayName("감사 한 겹을 씌울 뿐 조회 결과를 바꾸지 않는다")
    void itWrapsTheSearchWithoutAlteringWhatComesBack() {
        RecordingSearch search = new RecordingSearch();
        AuditLogQuery query = new AuditLogQuery("seller@lemuel.co.kr", 7L, AuditAction.BOARD_DELETED,
                "Board", "42", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 28), 0, 1);

        AuditLogExport export = new OperationAuditLogExportService(search).export(query);

        assertThat(search.seen.get()).isSameAs(query);
        assertThat(export.totalElements()).isEqualTo(1234L);
        assertThat(export.truncated()).isTrue();
    }
}
