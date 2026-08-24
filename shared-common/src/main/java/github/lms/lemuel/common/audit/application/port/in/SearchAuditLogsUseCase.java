package github.lms.lemuel.common.audit.application.port.in;

import github.lms.lemuel.common.audit.domain.AuditAction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 감사 로그 조회 유스케이스.
 *
 * <p><b>왜 이제야 조회가 생기는가</b>: {@code audit_logs} 는 오래전부터 적재되고 있었지만
 * 읽는 경로가 하나도 없었다. 정산 확정·환불·권한 변경·지급 실행이 전부 기록되는데 아무도
 * 볼 수 없다면, 그 테이블은 감사 증적이 아니라 디스크 사용량일 뿐이다. 감사는 "남겼다"가
 * 아니라 "확인할 수 있다"로 완성된다.
 *
 * <p><b>조회 축</b>은 인덱스와 일치시킨다 — {@code idx_audit_logs_actor_time},
 * {@code idx_audit_logs_action_time}, {@code idx_audit_logs_resource}. 그래서 필터는
 * actor / action / resource 셋과 기간이며, 정렬은 항상 {@code created_at DESC} 다.
 * 테이블이 {@code created_at} 기준 월별 RANGE 파티션이라 <b>기간이 곧 파티션 프루닝</b>이고,
 * 기간 없는 전수 조회는 전 파티션 스캔이 된다 — 그래서 기간 기본값을 서비스가 강제한다.
 *
 * <p><b>detail_json 을 그대로 내보내는 근거</b>: 기록기가 마스킹 계약을 지키고(주민등록번호
 * 패턴은 DB 트리거가 유입 자체를 거부), 이 API 는 ADMIN 전용이다. 여기서 한 번 더 가리면
 * 조작의 근거("무엇을 얼마로 바꿨는가")가 사라져 감사가 무의미해진다.
 */
public interface SearchAuditLogsUseCase {

    /** 조건에 맞는 감사 로그를 최신순 페이지로 조회한다. */
    AuditLogPage search(AuditLogQuery query);

    /**
     * 같은 조건의 액션별 건수. 목록을 넘기기 전에 "이 기간에 무슨 일이 얼마나 있었나"를
     * 먼저 보여주기 위한 요약이며, 건수 내림차순이다.
     */
    List<AuditActionCount> countByAction(AuditLogQuery query);

    /**
     * 내보내기용 전체 목록 — 페이지를 넘기지 않고 최신순으로 이어 붙인다.
     *
     * <p>상한이 있는 이유: 내보내기는 "화면에 보이는 것"이 아니라 "조건에 맞는 전부"를 요구하는
     * 조작이라, 기간을 넓게 잡으면 수백만 행이 된다. 응답을 만들다 서버가 죽는 대신
     * <b>상한에서 끊고 그 사실을 알린다</b> — 잘렸는지 모르는 CSV 가 감사 자료로 나가는 것이
     * 가장 나쁜 결말이다.
     */
    AuditLogExport export(AuditLogQuery query);

    /**
     * 내보내기 결과.
     *
     * @param truncated 상한에 걸려 잘렸는지 — 화면은 이 값을 반드시 사용자에게 알려야 한다
     */
    record AuditLogExport(List<AuditLogRow> rows, boolean truncated, long totalElements) {
    }

    /**
     * 조회 조건.
     *
     * @param actorEmail  행위자 이메일 부분일치(대소문자 무시). 공백/null 이면 미적용
     * @param actorId     행위자 ID 정확일치. null 이면 미적용
     * @param action      감사 액션 정확일치. null 이면 미적용
     * @param resourceType 리소스 유형 정확일치. 공백/null 이면 미적용
     * @param resourceId  리소스 식별자 정확일치. 공백/null 이면 미적용
     * @param from        조회 시작일(포함). null 이면 서비스가 기본값을 채운다
     * @param to          조회 종료일(포함 — 그날 23:59:59.999999 까지). null 이면 오늘
     * @param page        0-base 페이지 번호
     * @param size        페이지 크기
     */
    record AuditLogQuery(
            String actorEmail,
            Long actorId,
            AuditAction action,
            String resourceType,
            String resourceId,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
    }

    /** 한 페이지. */
    record AuditLogPage(
            List<AuditLogRow> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    /** 목록 한 줄. */
    record AuditLogRow(
            Long id,
            Long actorId,
            String actorEmail,
            String action,
            String resourceType,
            String resourceId,
            String detailJson,
            String ipAddress,
            String userAgent,
            LocalDateTime createdAt) {
    }

    /** 액션별 건수. */
    record AuditActionCount(String action, long count) {
    }
}
