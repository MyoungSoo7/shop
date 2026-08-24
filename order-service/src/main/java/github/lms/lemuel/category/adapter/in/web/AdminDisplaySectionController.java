package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.adapter.in.web.dto.DisplaySectionItemResponse;
import github.lms.lemuel.category.adapter.in.web.dto.DisplaySectionResponse;
import github.lms.lemuel.category.application.service.DisplaySectionService;
import github.lms.lemuel.category.domain.DisplaySectionKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 진열 편성 운영 콘솔. 편성은 무엇이 화면 앞에 오는지를 정하므로 ADMIN 으로 제한한다.
 */
@Tag(name = "Admin Display Section", description = "진열/기획전 편성 관리 API")
@RestController
@RequestMapping("/admin/display-sections")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDisplaySectionController {

    private final DisplaySectionService displaySectionService;

    public AdminDisplaySectionController(DisplaySectionService displaySectionService) {
        this.displaySectionService = displaySectionService;
    }

    /** 편성 생성 요청. 기간이 비면 그 방향은 열려 있다(상시 진열). */
    public record CreateSectionRequest(String code, String name, DisplaySectionKind kind,
                                       Long categoryId, LocalDateTime startsAt, LocalDateTime endsAt,
                                       Integer sortOrder) {
    }

    public record AddItemRequest(Long productId, Integer sortOrder, Boolean pinned) {
    }

    public record RescheduleRequest(LocalDateTime startsAt, LocalDateTime endsAt) {
    }

    @Operation(summary = "전체 편성 목록", description = "노출이 끝난 편성도 포함해 정렬 순서대로 반환한다.")
    @GetMapping
    public ResponseEntity<List<DisplaySectionResponse>> getAll() {
        return ResponseEntity.ok(displaySectionService.getAllSections().stream()
                .map(DisplaySectionResponse::from)
                .toList());
    }

    @Operation(summary = "편성에 담긴 상품", description = "노출이 끝난 편성의 내용도 그대로 보여 준다 — 편성을 짜는 자리다.")
    @GetMapping("/{code}/items")
    public ResponseEntity<List<DisplaySectionItemResponse>> getItems(
            @Parameter(description = "편성 코드", required = true) @PathVariable String code) {
        return ResponseEntity.ok(displaySectionService.getItems(code).stream()
                .map(DisplaySectionItemResponse::from)
                .toList());
    }

    @Operation(summary = "편성 생성", description = "CATEGORY_BEST 는 대상 카테고리가 필수다.")
    @PostMapping
    public ResponseEntity<DisplaySectionResponse> create(@RequestBody CreateSectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DisplaySectionResponse.from(
                displaySectionService.createSection(request.code(), request.name(), request.kind(),
                        request.categoryId(), request.startsAt(), request.endsAt(), request.sortOrder())));
    }

    @Operation(summary = "편성에 상품 추가", description = "같은 상품을 다시 추가하면 순서·고정만 갱신된다.")
    @PostMapping("/{code}/items")
    public ResponseEntity<Void> addItem(
            @Parameter(description = "편성 코드", required = true) @PathVariable String code,
            @RequestBody AddItemRequest request) {
        displaySectionService.addProduct(code, request.productId(), request.sortOrder(),
                Boolean.TRUE.equals(request.pinned()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "편성에서 상품 제거")
    @DeleteMapping("/{code}/items/{productId}")
    public ResponseEntity<Void> removeItem(@PathVariable String code, @PathVariable Long productId) {
        displaySectionService.removeProduct(code, productId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "편성 기간 변경", description = "끝난 편성을 되살리는 정상 경로이기도 하다.")
    @PatchMapping("/{code}/schedule")
    public ResponseEntity<DisplaySectionResponse> reschedule(@PathVariable String code,
                                                             @RequestBody RescheduleRequest request) {
        return ResponseEntity.ok(DisplaySectionResponse.from(
                displaySectionService.reschedule(code, request.startsAt(), request.endsAt())));
    }

    @Operation(summary = "편성 활성/비활성")
    @PatchMapping("/{code}/active")
    public ResponseEntity<DisplaySectionResponse> setActive(@PathVariable String code,
                                                            @RequestParam boolean active) {
        return ResponseEntity.ok(DisplaySectionResponse.from(
                displaySectionService.setActive(code, active)));
    }
}
