package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.request.CreateTagRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateTagRequest;
import github.lms.lemuel.product.adapter.in.web.response.TagResponse;
import github.lms.lemuel.product.application.port.in.TagUseCase;
import github.lms.lemuel.product.domain.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Product Tag", description = "상품 태그 관리 API")
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagUseCase tagUseCase;

    @Operation(summary = "태그 생성", description = "새 태그를 생성한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<TagResponse> createTag(@RequestBody CreateTagRequest request) {
        Tag tag = tagUseCase.createTag(request.getName(), request.getColor());
        return ResponseEntity.status(HttpStatus.CREATED).body(TagResponse.from(tag));
    }

    @Operation(summary = "태그 단건 조회", description = "ID로 태그를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "태그를 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<TagResponse> getTag(
            @Parameter(description = "태그 ID", required = true) @PathVariable Long id) {
        Tag tag = tagUseCase.getTagById(id);
        return ResponseEntity.ok(TagResponse.from(tag));
    }

    @Operation(summary = "전체 태그 조회", description = "등록된 모든 태그를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<List<TagResponse>> getAllTags() {
        List<Tag> tags = tagUseCase.getAllTags();
        return ResponseEntity.ok(tags.stream()
                .map(TagResponse::from)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "상품의 태그 조회", description = "상품 ID로 해당 상품에 붙은 태그 목록을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/product/{productId}")
    public ResponseEntity<List<TagResponse>> getTagsByProduct(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId) {
        List<Tag> tags = tagUseCase.getTagsByProductId(productId);
        return ResponseEntity.ok(tags.stream()
                .map(TagResponse::from)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "태그 수정", description = "태그 이름/색상을 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "404", description = "태그를 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public ResponseEntity<TagResponse> updateTag(
            @Parameter(description = "태그 ID", required = true) @PathVariable Long id,
            @RequestBody UpdateTagRequest request) {
        Tag tag = tagUseCase.updateTag(id, request.getName(), request.getColor());
        return ResponseEntity.ok(TagResponse.from(tag));
    }

    @Operation(summary = "태그 삭제", description = "태그를 삭제한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "태그를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTag(
            @Parameter(description = "태그 ID", required = true) @PathVariable Long id) {
        tagUseCase.deleteTag(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "상품에 태그 추가", description = "특정 상품에 태그를 연결한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추가 성공"),
            @ApiResponse(responseCode = "404", description = "상품 또는 태그를 찾을 수 없음")
    })
    @PostMapping("/product/{productId}/tag/{tagId}")
    public ResponseEntity<Void> addTagToProduct(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @Parameter(description = "태그 ID", required = true) @PathVariable Long tagId) {
        tagUseCase.addTagToProduct(productId, tagId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "상품에서 태그 제거", description = "특정 상품에 연결된 태그를 제거한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "제거 성공"),
            @ApiResponse(responseCode = "404", description = "상품 또는 태그를 찾을 수 없음")
    })
    @DeleteMapping("/product/{productId}/tag/{tagId}")
    public ResponseEntity<Void> removeTagFromProduct(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @Parameter(description = "태그 ID", required = true) @PathVariable Long tagId) {
        tagUseCase.removeTagFromProduct(productId, tagId);
        return ResponseEntity.ok().build();
    }
}
