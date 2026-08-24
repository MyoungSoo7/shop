package github.lms.lemuel.common.audit.application.service;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase;
import github.lms.lemuel.common.audit.application.port.out.SearchAuditLogsPort;
import github.lms.lemuel.common.audit.application.port.out.SearchAuditLogsPort.AuditLogCriteria;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 감사 로그 조회 서비스 — 조회 조건을 <b>정규화</b>하는 것이 이 계층의 일이다.
 *
 * <p><b>기간 기본값을 강제하는 이유</b>: {@code audit_logs} 는 {@code created_at} 월별 RANGE
 * 파티션이다. 기간이 비면 전 파티션을 스캔하므로, 화면이 파라미터를 빠뜨린 호출 하나로
 * 운영 DB 를 훑게 된다. 그래서 {@code from} 이 없으면 {@link #DEFAULT_RANGE_DAYS} 일 전으로
 * 채운다 — "전체 조회"라는 선택지를 아예 두지 않는다.
 *
 * <p><b>종료일 해석</b>: {@code to} 는 사용자가 고른 <b>날짜</b>이고 사람은 그날을 포함한다고
 * 읽는다. 그래서 경계는 {@code to + 1일}(미포함)이다. 이 변환은 여기 한 곳에서만 한다.
 *
 * <p><b>size 상한</b>: 감사 목록은 detail_json 을 통째로 실어 행이 무겁다. 상한이 없으면
 * {@code size=100000} 한 번에 응답이 수백 MB 가 된다.
 */
@Service
public class SearchAuditLogsService implements SearchAuditLogsUseCase {

    /** 기간 미지정 시 거슬러 올라가는 일수. */
    public static final int DEFAULT_RANGE_DAYS = 30;

    /** 한 페이지 최대 건수. */
    public static final int MAX_PAGE_SIZE = 200;

    /** 한 페이지 기본 건수. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * CSV 내보내기 최대 행수.
     *
     * <p>감사 로그 한 행은 detail_json 을 포함해 무겁다. 상한 없이 기간만 넓히면 응답 하나가
     * 수백 MB 가 되어 서버와 브라우저를 동시에 무너뜨린다. 넘치면 잘라내되 잘렸다는 사실을
     * 응답에 실어 보낸다.
     */
    public static final int MAX_EXPORT_ROWS = 5_000;

    private final SearchAuditLogsPort searchAuditLogsPort;

    public SearchAuditLogsService(SearchAuditLogsPort searchAuditLogsPort) {
        this.searchAuditLogsPort = searchAuditLogsPort;
    }

    @Override
    public AuditLogPage search(AuditLogQuery query) {
        AuditLogCriteria criteria = toCriteria(query);
        int page = normalizePage(query.page());
        int size = normalizeSize(query.size());

        long total = searchAuditLogsPort.count(criteria);
        List<AuditLogRow> content = total == 0
                ? List.of()
                : searchAuditLogsPort.search(criteria, page, size);

        int totalPages = (int) ((total + size - 1) / size);
        return new AuditLogPage(content, page, size, total, totalPages);
    }

    @Override
    public List<AuditActionCount> countByAction(AuditLogQuery query) {
        return searchAuditLogsPort.countByAction(toCriteria(query));
    }

    @Override
    public AuditLogExport export(AuditLogQuery query) {
        AuditLogCriteria criteria = toCriteria(query);
        long total = searchAuditLogsPort.count(criteria);
        if (total == 0) {
            return new AuditLogExport(List.of(), false, 0);
        }

        int wanted = (int) Math.min(total, MAX_EXPORT_ROWS);
        List<AuditLogRow> rows = new ArrayList<>(wanted);
        for (int page = 0; rows.size() < wanted; page++) {
            List<AuditLogRow> chunk = searchAuditLogsPort.search(criteria, page, MAX_PAGE_SIZE);
            if (chunk.isEmpty()) {
                // 조회 중 데이터가 줄어든 경우(리텐션 파티션 DROP 등). 무한 루프를 막는다.
                break;
            }
            for (AuditLogRow row : chunk) {
                if (rows.size() == wanted) {
                    break;
                }
                rows.add(row);
            }
        }

        return new AuditLogExport(rows, total > MAX_EXPORT_ROWS, total);
    }

    /**
     * 화면 질의를 어댑터가 그대로 쓸 수 있는 조건으로 바꾼다.
     *
     * <p>{@code from > to} 처럼 뒤집힌 기간은 거부하지 않고 <b>바로잡는다</b>. 운영자가 달력에서
     * 순서를 바꿔 고르는 일은 흔하고, 그때 에러를 던지면 아무것도 못 보게 될 뿐이다.
     */
    private AuditLogCriteria toCriteria(AuditLogQuery query) {
        LocalDate to = query.to() != null ? query.to() : LocalDate.now();
        LocalDate from = query.from() != null ? query.from() : to.minusDays(DEFAULT_RANGE_DAYS);
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }

        return new AuditLogCriteria(
                blankToNull(query.actorEmail()),
                query.actorId(),
                query.action() != null ? query.action().name() : null,
                blankToNull(query.resourceType()),
                blankToNull(query.resourceId()),
                from.atStartOfDay(),
                to.plusDays(1).atStartOfDay());
    }

    private static int normalizePage(int page) {
        return Math.max(page, 0);
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
