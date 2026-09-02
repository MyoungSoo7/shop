package github.lms.lemuel.batch.adapter.in.web;

import github.lms.lemuel.batch.adapter.out.persistence.BatchRunHistoryJpaEntity;
import github.lms.lemuel.batch.adapter.out.persistence.BatchRunHistoryJpaRepository;
import github.lms.lemuel.batch.application.BatchRerunService;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.domain.BatchRunStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 배치 실행 원장 콘솔.
 *
 * <pre>
 *   GET  /admin/batch-runs?batchName=&amp;status=&amp;targetDate=&amp;page=0&amp;size=50
 *   GET  /admin/batch-runs/latest
 *   GET  /admin/batch-runs/rerunnable
 *   POST /admin/batch-runs/{batchName}/rerun   { "targetDate": "2026-09-01", "dryRun": true }
 * </pre>
 *
 * <p>{@code /latest} 가 이 콘솔의 핵심이다. 목록에 <b>있는 것</b>보다 <b>마지막 성공이 언제인가</b>가
 * 운영자가 알아야 할 값이다 — 매일 도는 배치의 마지막 성공이 사흘 전이면 그 사흘이 구멍이고,
 * 지금까지는 그 사실을 알 방법이 아예 없었다.
 *
 * <p>재실행은 ADMIN 만 가능하다(SecurityConfig 의 {@code /admin/batch-runs/**}). 조회까지 같은
 * 등급으로 묶은 이유는 이 표가 실패 사유 문자열을 그대로 담기 때문이다.
 */
@RestController
@RequestMapping("/admin/batch-runs")
public class AdminBatchRunController {

    private static final int MAX_PAGE_SIZE = 200;

    private final BatchRunHistoryJpaRepository repository;
    private final BatchRerunService rerunService;

    public AdminBatchRunController(BatchRunHistoryJpaRepository repository, BatchRerunService rerunService) {
        this.repository = repository;
        this.rerunService = rerunService;
    }

    @Operation(summary = "배치 실행 이력 조회",
            description = "batchName·status·targetDate 로 걸러 최근 실행 순으로 조회한다. 인자는 모두 선택.")
    @GetMapping
    public ResponseEntity<Page<BatchRunView>> list(
            @RequestParam(name = "batchName", required = false) String batchName,
            @RequestParam(name = "status", required = false) BatchRunStatus status,
            @RequestParam(name = "targetDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<BatchRunView> result = repository
                .search(batchName, status, targetDate, PageRequest.of(Math.max(page, 0), bounded))
                .map(BatchRunView::from);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "배치별 최근 실행",
            description = "배치마다 가장 최근 실행 1건. 마지막 성공 시각이 뒤처진 배치가 곧 구멍이다.")
    @GetMapping("/latest")
    public ResponseEntity<List<BatchRunView>> latest() {
        return ResponseEntity.ok(repository.findLatestPerBatch().stream().map(BatchRunView::from).toList());
    }

    @Operation(summary = "재실행 가능한 배치 목록",
            description = "날짜를 지정해 다시 돌릴 수 있는 배치. 여기 없는 배치는 재실행 대상이 아니다.")
    @GetMapping("/rerunnable")
    public ResponseEntity<List<RerunnableView>> rerunnable() {
        return ResponseEntity.ok(rerunService.available().stream().map(RerunnableView::from).toList());
    }

    @Operation(summary = "배치 날짜 지정 재실행",
            description = "놓친 날짜분을 다시 처리한다. dryRun=true 면 대상만 세고 상태를 바꾸지 않는다.")
    @PostMapping("/{batchName}/rerun")
    public ResponseEntity<RerunResult> rerun(@PathVariable("batchName") String batchName,
                                             // @Valid 가 없으면 아래 RerunRequest 의 @NotNull 은 그냥 주석이다.
                                             // targetDate 가 널인 채로 내려가면 재실행이 "어느 날짜분인지
                                             // 모르는 채로" 돌고, 배치마다 다르게 터진다.
                                             @Valid @RequestBody RerunRequest request) {
        int processed = rerunService.rerun(batchName, request.targetDate(), request.dryRunOrFalse(), actor());
        return ResponseEntity.ok(new RerunResult(
                batchName, request.targetDate(), request.dryRunOrFalse(), processed));
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "admin" : "admin:" + authentication.getName();
    }

    /**
     * @param targetDate 다시 돌릴 날짜분
     * @param dryRun     널이면 거짓. 실수로 실제 실행되는 쪽이 아니라 <b>안 도는 쪽</b>이 기본값이어야
     *                   하지만, 여기서는 반대로 두면 "재실행" 이라는 명시적 호출이 아무것도 안 하게 되어
     *                   더 헷갈린다 — 그래서 기본은 실제 실행이고, dry-run 은 명시해야 한다.
     */
    public record RerunRequest(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate,
            Boolean dryRun) {

        boolean dryRunOrFalse() {
            return dryRun != null && dryRun;
        }
    }

    public record RerunResult(String batchName, LocalDate targetDate, boolean dryRun, int processedCount) {
    }

    public record RerunnableView(String batchName, String description, boolean supportsDryRun) {
        static RerunnableView from(RerunnableBatch batch) {
            return new RerunnableView(batch.batchName(), batch.description(), batch.supportsDryRun());
        }
    }

    /**
     * @param triggeredBy {@code scheduler} 면 정규 실행, {@code rerun:admin:...} 이면 사람이 돌린 것.
     *                    같은 날짜가 두 번 계산된 이유를 나중에 여기서 읽는다.
     */
    public record BatchRunView(Long id, String batchName, String runId, LocalDate targetDate,
                               BatchRunStatus status, LocalDateTime startedAt, LocalDateTime completedAt,
                               Integer processedCount, String errorMessage, String triggeredBy) {

        static BatchRunView from(BatchRunHistoryJpaEntity entity) {
            return new BatchRunView(entity.getId(), entity.getBatchName(), entity.getRunId(),
                    entity.getTargetDate(), entity.getStatus(), entity.getStartedAt(),
                    entity.getCompletedAt(), entity.getProcessedCount(), entity.getErrorMessage(),
                    entity.getTriggeredBy());
        }
    }
}
