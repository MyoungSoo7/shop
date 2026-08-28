package github.lms.lemuel.operation.audit.application.port.in;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogExport;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;

/**
 * 감사 로그를 파일로 <b>반출</b>하는 유스케이스.
 *
 * <p>조회({@code SearchAuditLogsUseCase.export})와 굳이 인터페이스를 나누는 이유는 둘이 다른
 * 사건이기 때문이다. 화면 조회는 서버 밖으로 아무것도 남기지 않지만, 반출은 다른 사람의
 * 이메일·IP·조작 상세가 담긴 파일을 운영자 노트북에 만들어 놓는다. 그 사건에 감사 행 하나를
 * 붙이려면 감사 애스펙트가 가로챌 수 있는 <b>자기 경계</b>가 필요하다.
 *
 * <p>공용 유스케이스에 {@code @Auditable} 을 직접 붙이지 않는 것은 그 인터페이스를 다른
 * 서비스도 쓰기 때문이다. 거기에 붙이면 이 서비스의 감사 어휘가 남의 서비스에 새어 나간다.
 */
public interface ExportOperationAuditLogsUseCase {

    /** 조건에 맞는 감사 로그를 상한까지 모아 돌려주고, 그 반출 사실을 감사에 남긴다. */
    AuditLogExport export(AuditLogQuery query);
}
