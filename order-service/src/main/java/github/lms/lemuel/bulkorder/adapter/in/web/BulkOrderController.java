package github.lms.lemuel.bulkorder.adapter.in.web;

import github.lms.lemuel.bulkorder.application.port.in.BulkOrderUseCase;
import github.lms.lemuel.bulkorder.domain.BulkOrderCell;
import github.lms.lemuel.bulkorder.domain.BulkOrderColumnSpec;
import github.lms.lemuel.bulkorder.domain.BulkOrderDraft;
import github.lms.lemuel.bulkorder.domain.BulkOrderRow;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 대량주문 콘솔 — 업로드·검증·확정·폐기.
 *
 * <pre>
 *   POST   /api/bulk-orders                (multipart: file)  → 업로드 + 즉시 검증
 *   GET    /api/bulk-orders                                   → 내 초안 목록
 *   GET    /api/bulk-orders/columns                           → 업로드 양식(열 정의)
 *   GET    /api/bulk-orders/{id}                              → 행·셀 오류까지 상세
 *   POST   /api/bulk-orders/{id}/revalidate                   → 고친 뒤 재검증
 *   POST   /api/bulk-orders/{id}/confirm                      → 실주문 전환
 *   DELETE /api/bulk-orders/{id}                              → 폐기
 * </pre>
 *
 * <p>업로드와 확정이 <b>다른 엔드포인트</b>인 것이 이 기능의 요점이다 — 올리는 순간 주문이 나가면
 * 뒷쪽 한 행의 오타 때문에 앞쪽 수백 건을 취소·환불로 되돌려야 한다.
 *
 * <p>주체는 JWT 에서 파생하고, 초안은 올린 사람만 볼 수 있다. 대량주문 파일에는 수백 명의 수령인
 * 이름·연락처·주소가 들어 있어, 남의 초안이 열리는 순간 그것이 곧 개인정보 유출이다.
 */
@Tag(name = "Bulk Order", description = "대량주문 업로드·검증·확정")
@RestController
@RequestMapping("/api/bulk-orders")
public class BulkOrderController {

    private final BulkOrderUseCase bulkOrderUseCase;
    private final BulkOrderCsvParser parser;

    public BulkOrderController(BulkOrderUseCase bulkOrderUseCase, BulkOrderCsvParser parser) {
        this.bulkOrderUseCase = bulkOrderUseCase;
        this.parser = parser;
    }

    @Operation(summary = "대량주문 업로드 + 검증",
            description = "CSV 를 행으로 쪼개 초안으로 저장하고 곧바로 검증한다. 주문은 아직 나가지 않는다.")
    @PostMapping
    public ResponseEntity<DraftResponse> upload(@RequestParam("file") MultipartFile file) {
        long userId = callerId();
        try (var in = file.getInputStream()) {
            BulkOrderDraft draft = bulkOrderUseCase.uploadAndValidate(
                    userId, file.getOriginalFilename(), parser.parse(in));
            return ResponseEntity.ok(DraftResponse.detail(draft));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Operation(summary = "내 대량주문 초안 목록")
    @GetMapping
    public ResponseEntity<List<DraftResponse>> list() {
        return ResponseEntity.ok(bulkOrderUseCase.listMine(callerId()).stream()
                .map(DraftResponse::summary)
                .toList());
    }

    @Operation(summary = "업로드 양식(열 정의)",
            description = "화면이 헤더와 입력 안내를 그릴 때 쓴다. 양식은 DB 에 있어 배포 없이 바뀐다.")
    @GetMapping("/columns")
    public ResponseEntity<List<ColumnResponse>> columns() {
        return ResponseEntity.ok(bulkOrderUseCase.columnSpecs().stream()
                .map(ColumnResponse::from)
                .toList());
    }

    @Operation(summary = "초안 상세", description = "행·셀 단위 오류까지 돌려준다 — 화면이 틀린 칸을 짚을 수 있게.")
    @GetMapping("/{id}")
    public ResponseEntity<DraftResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(DraftResponse.detail(bulkOrderUseCase.get(id, callerId())));
    }

    @Operation(summary = "재검증", description = "값을 고친 뒤 다시 검증한다. 오류가 사라지면 확정이 열린다.")
    @PostMapping("/{id}/revalidate")
    public ResponseEntity<DraftResponse> revalidate(@PathVariable Long id) {
        return ResponseEntity.ok(DraftResponse.detail(bulkOrderUseCase.revalidate(id, callerId())));
    }

    @Operation(summary = "실주문 전환",
            description = "전 행이 통과한 초안만 확정된다. 행 단위 독립 커밋이라 일부 실패해도 나머지는 유지되고, "
                    + "이미 주문이 나간 행은 재확정에서 건너뛴다.")
    @PostMapping("/{id}/confirm")
    public ResponseEntity<BulkOrderUseCase.ConfirmResult> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(bulkOrderUseCase.confirm(id, callerId()));
    }

    @Operation(summary = "초안 폐기")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> discard(@PathVariable Long id) {
        bulkOrderUseCase.discard(id, callerId());
        return ResponseEntity.noContent().build();
    }

    private long callerId() {
        return ResourceOwnership.callerUserId(SecurityContextHolder.getContext().getAuthentication());
    }

    public record ColumnResponse(int columnIndex, String itemCode, String name,
                                 boolean required, Integer maxLength, String validationType,
                                 String validationText) {
        static ColumnResponse from(BulkOrderColumnSpec spec) {
            return new ColumnResponse(spec.columnIndex(), spec.itemCode(), spec.name(),
                    spec.required(), spec.maxLength(), spec.validationType().name(),
                    spec.validationText());
        }
    }

    public record CellResponse(int columnIndex, String value, boolean valid, String errorMessage) {
        static CellResponse from(BulkOrderCell cell) {
            return new CellResponse(cell.getColumnIndex(), cell.getValue(),
                    cell.isValid(), cell.getErrorMessage());
        }
    }

    public record RowResponse(int rowNumber, boolean valid, String errorMessage,
                              Long createdOrderId, List<CellResponse> cells) {
        static RowResponse from(BulkOrderRow row) {
            return new RowResponse(row.getRowNumber(), row.isValid(), row.getErrorMessage(),
                    row.getCreatedOrderId(), row.getCells().stream().map(CellResponse::from).toList());
        }
    }

    public record DraftResponse(Long id, String fileName, String status, int rowCount,
                                long validRowCount, LocalDateTime uploadedAt,
                                LocalDateTime updatedAt, List<RowResponse> rows) {

        static DraftResponse detail(BulkOrderDraft draft) {
            return new DraftResponse(draft.getId(), draft.getFileName(), draft.getStatus().name(),
                    draft.getRows().size(), draft.validRowCount(), draft.getUploadedAt(),
                    draft.getUpdatedAt(), draft.getRows().stream().map(RowResponse::from).toList());
        }

        /** 목록용 — 행을 싣지 않는다(요약 화면에 수천 셀을 내려보낼 이유가 없다). */
        static DraftResponse summary(BulkOrderDraft draft) {
            return new DraftResponse(draft.getId(), draft.getFileName(), draft.getStatus().name(),
                    draft.getRows().size(), draft.validRowCount(), draft.getUploadedAt(),
                    draft.getUpdatedAt(), List.of());
        }
    }
}
