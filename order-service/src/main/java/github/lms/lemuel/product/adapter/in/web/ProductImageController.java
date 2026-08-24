package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.dto.ImageReorderRequest;
import github.lms.lemuel.product.adapter.in.web.dto.ProductImageResponse;
import github.lms.lemuel.product.application.service.ProductImageService;
import github.lms.lemuel.product.domain.ImageUpload;
import github.lms.lemuel.product.domain.ProductImage;
import github.lms.lemuel.product.domain.exception.ImageStorageException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Product Image (Admin)", description = "관리자 상품 이미지 업로드/관리 API")
@RestController
@RequestMapping("/admin/products/{productId}/images")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ProductImageController {

    private final ProductImageService imageService;

    /**
     * 이미지 업로드 (다중)
     */
    @Operation(summary = "이미지 다중 업로드", description = "상품에 여러 이미지를 업로드한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "업로드 성공"),
            @ApiResponse(responseCode = "400", description = "파일 없음 등 잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @PostMapping
    public ResponseEntity<List<ProductImageResponse>> uploadImages(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @RequestParam("files") List<MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<ProductImage> images = imageService.uploadImages(productId, toUploads(files));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(images.stream()
                        .map(ProductImageResponse::from)
                        .collect(Collectors.toList()));
    }

    /**
     * 멀티파트 요청을 도메인 값 오브젝트로 옮긴다 — {@code MultipartFile} 은 이 web 어댑터가 끝이다.
     *
     * <p>본문을 읽다 발생하는 {@code IOException}(체크 예외)도 여기서 끊는다. 응용 서비스로
     * {@code throws} 를 전파하는 대신 의미 있는 언체크 예외로 번역한다.
     */
    private List<ImageUpload> toUploads(List<MultipartFile> files) {
        return files.stream().map(file -> {
            try {
                return ImageUpload.of(file.getOriginalFilename(), file.getContentType(),
                        file.getSize(), file.getBytes());
            } catch (IOException e) {
                throw new ImageStorageException(
                        "업로드 파일 본문을 읽지 못했습니다: " + file.getOriginalFilename(), e);
            }
        }).collect(Collectors.toList());
    }

    /**
     * 이미지 목록 조회
     */
    @Operation(summary = "상품 이미지 목록 조회", description = "상품에 등록된 모든 이미지를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<List<ProductImageResponse>> getImages(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId) {
        List<ProductImage> images = imageService.getProductImages(productId);
        return ResponseEntity.ok(images.stream()
                .map(ProductImageResponse::from)
                .collect(Collectors.toList()));
    }

    /**
     * 대표 이미지 지정
     */
    @Operation(summary = "대표 이미지 지정", description = "특정 이미지를 대표 이미지로 설정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 성공"),
            @ApiResponse(responseCode = "404", description = "이미지를 찾을 수 없음")
    })
    @PatchMapping("/{imageId}/primary")
    public ResponseEntity<ProductImageResponse> setPrimaryImage(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @Parameter(description = "이미지 ID", required = true) @PathVariable Long imageId) {

        ProductImage image = imageService.setPrimaryImage(productId, imageId);
        return ResponseEntity.ok(ProductImageResponse.from(image));
    }

    /**
     * 이미지 순서 변경
     */
    @Operation(summary = "이미지 순서 변경", description = "이미지 목록의 노출 순서를 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공")
    })
    @PatchMapping("/reorder")
    public ResponseEntity<List<ProductImageResponse>> reorderImages(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @RequestBody ImageReorderRequest request) {

        List<ProductImage> images = imageService.reorderImages(productId, request.getImageIds());
        return ResponseEntity.ok(images.stream()
                .map(ProductImageResponse::from)
                .collect(Collectors.toList()));
    }

    /**
     * 이미지 삭제
     */
    @Operation(summary = "이미지 삭제", description = "특정 이미지를 삭제한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "이미지를 찾을 수 없음")
    })
    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> deleteImage(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @Parameter(description = "이미지 ID", required = true) @PathVariable Long imageId) {

        imageService.deleteImage(productId, imageId);
        return ResponseEntity.noContent().build();
    }
}
