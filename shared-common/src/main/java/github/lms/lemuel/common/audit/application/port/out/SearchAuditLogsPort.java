package github.lms.lemuel.common.audit.application.port.out;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 감사 로그 조회 포트.
 *
 * <p>기간은 이미 <b>정규화된 {@link LocalDateTime} 반개구간</b>({@code from} 이상 {@code toExclusive}
 * 미만)으로 받는다. "종료일 포함"을 어떻게 해석할지는 정책이라 서비스가 정하고, 어댑터는
 * 경계 계산을 다시 하지 않는다 — 두 곳에서 계산하면 언젠가 하루가 어긋난다.
 */
public interface SearchAuditLogsPort {

    /** 조건에 맞는 로그를 {@code created_at DESC} 로 한 페이지 조회한다. */
    List<AuditLogRow> search(AuditLogCriteria criteria, int page, int size);

    /** 같은 조건의 총 건수. */
    long count(AuditLogCriteria criteria);

    /** 같은 조건의 액션별 건수(건수 내림차순). */
    List<AuditActionCount> countByAction(AuditLogCriteria criteria);

    /**
     * 정규화된 조회 조건.
     *
     * @param toExclusive 종료 경계(미포함)
     */
    record AuditLogCriteria(
            String actorEmail,
            Long actorId,
            String action,
            String resourceType,
            String resourceId,
            LocalDateTime from,
            LocalDateTime toExclusive) {
    }
}
