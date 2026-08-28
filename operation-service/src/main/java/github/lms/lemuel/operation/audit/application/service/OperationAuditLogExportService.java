package github.lms.lemuel.operation.audit.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogExport;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.operation.audit.application.port.in.ExportOperationAuditLogsUseCase;
import org.springframework.stereotype.Service;

/**
 * 감사 로그 반출 — 조회 유스케이스에 <b>감사 한 겹</b>을 씌운다.
 *
 * <p>기록을 남기는 조작은 전부 감사에 걸려 있었는데, 그 <b>기록을 통째로 내려받는 조작</b>만
 * 아무 흔적도 남기지 않고 있었다. 파일에는 다른 운영자의 이메일·접속 IP·조작 상세가 그대로
 * 들어간다. 사고 후 "누가 이 기간 감사 기록을 받아 갔는가"를 물으면 답할 방법이 없었다.
 *
 * <p><b>조회 조건을 상세에 남기는 이유</b>: 받아 간 파일이 무엇이었는지는 조건으로만 복원된다.
 * 행 내용을 복사해 두는 것은 감사 테이블을 두 배로 만드는 일이고, 조건 + 시각 + 건수가 있으면
 * 같은 질의를 다시 돌려 그때 나간 것이 무엇이었는지 재구성할 수 있다.
 *
 * <p><b>건수·잘림 여부도 남긴다</b>: 상한에 걸려 잘린 반출과 전량 반출은 유출 범위가 다르다.
 * 사후 평가에서 그 둘을 구분하지 못하면 피해 산정이 최악값으로 고정된다.
 *
 * <p>실패도 기록한다({@code recordOnFailure} 기본값). 권한이 없거나 질의가 터진 반출 <b>시도</b>는
 * 성공한 반출만큼이나 알아야 하는 사건이다.
 */
@Service
public class OperationAuditLogExportService implements ExportOperationAuditLogsUseCase {

    private final SearchAuditLogsUseCase searchAuditLogsUseCase;

    public OperationAuditLogExportService(SearchAuditLogsUseCase searchAuditLogsUseCase) {
        this.searchAuditLogsUseCase = searchAuditLogsUseCase;
    }

    @Override
    @Auditable(
            action = AuditAction.OPERATION_AUDIT_LOG_EXPORTED,
            resourceType = "AuditLog",
            // 단일 리소스가 아니라 조건에 걸린 집합이라 resourceId 는 비운다. 억지로 무언가를
            // 채우면 resource 축 조회가 실제로 없는 대상을 가리키게 된다.
            detail = "{"
                    + "'actorEmail': #p0.actorEmail(),"
                    + "'actorId': #p0.actorId(),"
                    + "'action': #p0.action() == null ? null : #p0.action().name(),"
                    + "'resourceType': #p0.resourceType(),"
                    + "'resourceId': #p0.resourceId(),"
                    + "'from': #p0.from() == null ? null : #p0.from().toString(),"
                    + "'to': #p0.to() == null ? null : #p0.to().toString(),"
                    + "'exportedRows': #result == null ? null : #result.rows().size(),"
                    + "'matchedTotal': #result == null ? null : #result.totalElements(),"
                    + "'truncated': #result == null ? null : #result.truncated()"
                    + "}"
    )
    public AuditLogExport export(AuditLogQuery query) {
        return searchAuditLogsUseCase.export(query);
    }
}
