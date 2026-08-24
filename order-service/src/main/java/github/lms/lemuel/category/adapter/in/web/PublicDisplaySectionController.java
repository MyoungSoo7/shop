package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.adapter.in.web.dto.DisplaySectionItemResponse;
import github.lms.lemuel.category.adapter.in.web.dto.DisplaySectionResponse;
import github.lms.lemuel.category.application.service.DisplaySectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 진열 편성 공개 조회.
 *
 * <p>노출 판정(기간·활성)은 서버가 한다 — 클라이언트가 기간을 보고 거르게 하면 시계가 어긋난 단말에서
 * 끝난 기획전이 보인다.
 */
@Tag(name = "Display Section", description = "진열/기획전 공개 조회 API")
@RestController
@RequestMapping("/display-sections")
public class PublicDisplaySectionController {

    private final DisplaySectionService displaySectionService;

    public PublicDisplaySectionController(DisplaySectionService displaySectionService) {
        this.displaySectionService = displaySectionService;
    }

    @Operation(summary = "노출 중인 편성 목록", description = "지금 시각 기준으로 노출 중인 편성만 정렬 순서대로 반환한다.")
    @GetMapping
    public ResponseEntity<List<DisplaySectionResponse>> getVisibleSections() {
        return ResponseEntity.ok(displaySectionService.getVisibleSections().stream()
                .map(DisplaySectionResponse::from)
                .toList());
    }

    @Operation(summary = "편성에 담긴 상품",
            description = "고정 상품이 앞, 그 다음 정렬 순서로 반환한다. 노출 중이 아닌 편성은 404 — 코드를 안다고 "
                    + "시작 전 기획전의 라인업을 미리 볼 수는 없다.")
    @GetMapping("/{code}/items")
    public ResponseEntity<List<DisplaySectionItemResponse>> getItems(
            @Parameter(description = "편성 코드", required = true) @PathVariable String code) {
        return ResponseEntity.ok(displaySectionService.getVisibleItems(code).stream()
                .map(DisplaySectionItemResponse::from)
                .toList());
    }
}
