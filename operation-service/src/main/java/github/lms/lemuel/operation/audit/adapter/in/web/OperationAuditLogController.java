package github.lms.lemuel.operation.audit.adapter.in.web;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogExport;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogPage;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogRow;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.web.csv.CsvResponse;
import github.lms.lemuel.common.web.csv.ExportScope;
import github.lms.lemuel.operation.audit.application.port.in.ExportOperationAuditLogsUseCase;
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
import java.util.List;
import java.util.Objects;

/**
 * 운영 콘솔 감사 로그 조회.
 *
 * <pre>
 *   GET /api/ops/audit-logs                → 조건 검색(최신순 페이지)
 *   GET /api/ops/audit-logs/action-counts  → 같은 조건의 액션별 건수(목록 위 요약)
 *   GET /api/ops/audit-logs/actions        → 필터 드롭다운용 액션 목록
 *   GET /api/ops/audit-logs/export         → 같은 조건의 CSV 내려받기
 * </pre>
 *
 * <p><b>왜 지금 생기는가</b>: operation-service 는 루트 스캔으로 shared-common 의 감사 설비를
 * 통째로 물고 있고 자기 DB 에 파티션·인덱스까지 갖춘 {@code audit_logs} 도 갖고 있었지만,
 * 아무도 쓰지 않았고 읽는 경로도 없었다. 게시판 관리 조작이 이제 여기에 쌓이므로 읽는 쪽도 같이
 * 연다 — <b>남기기만 하고 볼 수 없는 기록은 감사 증적이 아니다.</b> 알림 발송 이력에서 같은
 * 실수를 한 번 했다(기록은 남는데 조회가 없어 "그 사람한테 갔나"를 답할 수 없었다).
 *
 * <p><b>왜 {@code /admin/audit-logs} 가 아닌가</b>: 그 경로는 게이트웨이에서 이미 order-service 로
 * 간다(커머스 감사). 감사 테이블은 서비스마다 자기 DB 에 따로 있고 MSA 경계상 한쪽이 다른 쪽을
 * 읽을 수 없으므로 표면도 서비스마다 따로다. 이 서비스의 표면은 {@code /api/ops/**} 아래에 둔다 —
 * 게이트웨이 라우트와 ADMIN 권한 매처가 이미 그 접두사로 서 있어 배선을 새로 뚫지 않는다.
 *
 * <p>권한은 {@code OperationSecurityConfig} 의 {@code /api/ops/**} 체인이 ROLE_ADMIN 으로 막는다.
 *
 * <p>구조는 order-service 의 {@code AdminAuditLogController} 와 같다. 공용화하지 않는 이유는
 * 두 서비스가 서로의 클래스패스에 없기 때문이며, 이는 이 저장소가 감사 표면을 서비스마다 따로
 * 두는 것과 같은 이유다.
 */
@Tag(name = "Operation Audit Log", description = "운영 콘솔 감사 로그 조회(ADMIN)")
@RestController
@RequestMapping("/api/ops/audit-logs")
public class OperationAuditLogController {

    /**
     * 이 서비스가 실제로 남기는 액션만 드롭다운에 노출한다.
     *
     * <p>공용 enum 에는 정산·대출·보험 등 다른 서비스의 액션이 70개 넘게 들어 있다. 그걸 그대로
     * 내보내면 운영자가 고른 값 대부분이 <b>영원히 0건</b>이라, 필터가 "없는 것"과 "안 하는 것"을
     * 구분하지 못하게 만든다.
     *
     * <p>이 목록이 뒤처지면 새로 생긴 조작이 필터에서 조용히 빠진다 — 감사에서 보이지 않는 액션은
     * 없는 것과 같다. 그래서 {@code OperationAuditActionsTest} 가 이 상수를 코드에 붙은
     * {@code @Auditable} 전수와 대조해 어긋나면 실패한다.
     */
    static final List<AuditAction> OPERATION_ACTIONS = List.of(
            AuditAction.BOARD_CREATED,
            AuditAction.BOARD_UPDATED,
            AuditAction.BOARD_DEACTIVATED,
            AuditAction.BOARD_ACTIVATED,
            AuditAction.BOARD_DELETED,
            AuditAction.OPERATION_AUDIT_LOG_EXPORTED);

    private final SearchAuditLogsUseCase searchAuditLogsUseCase;
    private final ExportOperationAuditLogsUseCase exportAuditLogsUseCase;

    public OperationAuditLogController(SearchAuditLogsUseCase searchAuditLogsUseCase,
                                       ExportOperationAuditLogsUseCase exportAuditLogsUseCase) {
        this.searchAuditLogsUseCase = searchAuditLogsUseCase;
        this.exportAuditLogsUseCase = exportAuditLogsUseCase;
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

    @GetMapping("/actions")
    @Operation(summary = "감사 액션 목록", description = "필터 드롭다운용 — 이 서비스가 남기는 액션만")
    public ResponseEntity<List<String>> actions() {
        return ResponseEntity.ok(OPERATION_ACTIONS.stream().map(Enum::name).sorted().toList());
    }

    /**
     * 같은 조건의 CSV.
     *
     * <p>잘렸는지를 응답 헤더 {@code X-Export-Truncated}·{@code X-Export-Total} 로 알린다.
     * 본문에 경고 행을 끼우면 그 행이 데이터로 읽혀 집계를 오염시키므로, 메타는 메타 자리에 둔다.
     *
     * <p>조회와 달리 {@link ExportOperationAuditLogsUseCase} 를 거친다 — <b>반출은 그 자체가
     * 감사 대상</b>이라 애스펙트가 가로챌 경계가 필요하기 때문이다. 자세한 이유는 그 구현
     * ({@code OperationAuditLogExportService}) 에 적혀 있다.
     */
    @GetMapping("/export")
    @Operation(summary = "감사 로그 CSV", description = "화면과 같은 조건으로 상한까지 내려받는다")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        AuditLogExport export = exportAuditLogsUseCase.export(
                toQuery(actorEmail, actorId, action, resourceType, resourceId, from, to, 0, 1));

        return CsvResponse.of(
                "operation-audit-logs",
                List.of("일시", "행위자ID", "행위자", "액션", "리소스유형", "리소스ID", "IP", "상세"),
                export.rows(),
                OperationAuditLogController::toCells,
                ExportScope.of(export.totalElements(), export.truncated()));
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
