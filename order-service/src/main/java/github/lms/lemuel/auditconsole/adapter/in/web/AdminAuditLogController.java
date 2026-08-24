package github.lms.lemuel.auditconsole.adapter.in.web;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogExport;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogPage;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogRow;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.web.csv.CsvResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 커머스 감사 로그 조회 콘솔.
 *
 * <pre>
 *   GET /admin/audit-logs                → 조건 검색(최신순 페이지)
 *   GET /admin/audit-logs/action-counts  → 같은 조건의 액션별 건수(목록 위 요약)
 *   GET /admin/audit-logs/actions        → 필터 드롭다운용 액션 목록
 *   GET /admin/audit-logs/export         → 같은 조건의 CSV 내려받기
 * </pre>
 *
 * <p><b>왜 지금 생기는가</b>: {@code audit_logs} 는 로그인 실패·권한 변경·환불 같은 민감 조작을
 * 오래전부터 적재해 왔지만 읽는 경로가 없었다. 남기기만 하고 볼 수 없는 기록은 감사 증적이
 * 아니다 — 사고가 났을 때 DB 에 직접 붙는 것 말고는 방법이 없었고, 그 접근 자체가 감사 대상이다.
 *
 * <p><b>정산 쪽 감사는 여기 없다</b>: settlement-service 는 자기 DB({@code settlement_db})에
 * 자기 {@code audit_logs} 를 쌓는다(MSA 경계상 order 가 그 테이블을 읽을 수 없다). 지급 실행·
 * 차지백 판정 같은 자금 조작 이력은 {@code /admin/audit-trail}(settlement-service)이 답한다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/audit-logs/**} 매처(ADMIN)로 제한된다. 이 설정에는
 * 포괄 {@code /admin/**} 매처가 없으므로, 명시하지 않으면 {@code anyRequest().authenticated()} 로
 * 새어 일반 사용자도 남의 조작 이력을 읽게 된다.
 */
@Tag(name = "Admin Audit Log", description = "커머스 감사 로그 조회")
@RestController
@RequestMapping("/admin/audit-logs")
public class AdminAuditLogController {

    private final SearchAuditLogsUseCase searchAuditLogsUseCase;

    public AdminAuditLogController(SearchAuditLogsUseCase searchAuditLogsUseCase) {
        this.searchAuditLogsUseCase = searchAuditLogsUseCase;
    }

    @GetMapping
    @Operation(summary = "감사 로그 검색", description = "기간·행위자·액션·리소스로 좁혀 최신순으로 조회한다")
    public ResponseEntity<AuditLogPage> search(
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(searchAuditLogsUseCase.search(
                toQuery(actorEmail, actorId, action, resourceType, resourceId, from, to, page, size)));
    }

    @GetMapping("/action-counts")
    @Operation(summary = "액션별 건수", description = "목록을 넘기기 전에 '무슨 일이 얼마나 있었나'를 먼저 보여준다")
    public ResponseEntity<List<AuditActionCount>> actionCounts(
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok(searchAuditLogsUseCase.countByAction(
                toQuery(actorEmail, actorId, action, resourceType, resourceId, from, to, 0, 1)));
    }

    /**
     * 필터 드롭다운이 쓸 액션 목록.
     *
     * <p>화면에 문자열을 하드코딩하면 액션이 늘어날 때마다 프론트가 뒤처져, 새로 생긴 조작이
     * 필터에서 조용히 빠진다 — 감사에서 "보이지 않는 액션"은 없는 것과 같다.
     */
    @GetMapping("/actions")
    @Operation(summary = "감사 액션 목록", description = "필터 드롭다운용 — 서버 enum 이 정본이다")
    public ResponseEntity<List<String>> actions() {
        return ResponseEntity.ok(Arrays.stream(AuditAction.values()).map(Enum::name).sorted().toList());
    }

    /**
     * 같은 조건의 CSV.
     *
     * <p>잘렸는지를 응답 헤더 {@code X-Export-Truncated}·{@code X-Export-Total} 로 알린다.
     * 본문에 경고 행을 끼우면 그 행이 데이터로 읽혀 집계를 오염시키므로, 메타는 메타 자리에 둔다.
     */
    @GetMapping("/export")
    @Operation(summary = "감사 로그 CSV", description = "화면과 같은 조건으로 최대 5000행을 내려받는다")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        AuditLogExport export = searchAuditLogsUseCase.export(
                toQuery(actorEmail, actorId, action, resourceType, resourceId, from, to, 0, 1));

        ResponseEntity<ByteArrayResource> csv = CsvResponse.of(
                "audit-logs",
                List.of("일시", "행위자ID", "행위자", "액션", "리소스유형", "리소스ID", "IP", "상세"),
                export.rows(),
                AdminAuditLogController::toCells);

        return ResponseEntity.status(csv.getStatusCode())
                .headers(csv.getHeaders())
                .header("X-Export-Truncated", String.valueOf(export.truncated()))
                .header("X-Export-Total", String.valueOf(export.totalElements()))
                .body(csv.getBody());
    }

    private static List<String> toCells(AuditLogRow row) {
        return List.of(
                Objects.toString(row.createdAt(), ""),
                Objects.toString(row.actorId(), ""),
                Objects.toString(row.actorEmail(), ""),
                Objects.toString(row.action(), ""),
                Objects.toString(row.resourceType(), ""),
                Objects.toString(row.resourceId(), ""),
                Objects.toString(row.ipAddress(), ""),
                Objects.toString(row.detailJson(), ""));
    }

    /**
     * 문자열 action 을 enum 으로 옮긴다.
     *
     * <p>모르는 이름은 <b>필터 미적용</b>으로 흘린다. 400 을 던지면 화면이 옛 액션 이름을 캐시한
     * 순간 감사 조회가 통째로 막히는데, 필터 하나 때문에 이력 전체를 못 보게 되는 편이 더 나쁘다.
     */
    private static AuditLogQuery toQuery(String actorEmail, Long actorId, String action,
                                         String resourceType, String resourceId,
                                         LocalDate from, LocalDate to, int page, int size) {
        AuditAction parsed = null;
        if (action != null && !action.isBlank()) {
            try {
                parsed = AuditAction.valueOf(action.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                parsed = null;
            }
        }
        return new AuditLogQuery(actorEmail, actorId, parsed, resourceType, resourceId,
                from, to, page, size);
    }
}
