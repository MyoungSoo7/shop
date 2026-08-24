package github.lms.lemuel.review.adapter.in.web;

import github.lms.lemuel.review.adapter.in.web.dto.ReviewRequest;
import github.lms.lemuel.review.adapter.in.web.dto.ReviewResponse;
import github.lms.lemuel.review.adapter.in.web.dto.ReviewUpdateRequest;
import github.lms.lemuel.review.application.ReviewService;
import github.lms.lemuel.review.domain.Review;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 리뷰 & 평점 REST API
 * POST   /reviews                      - 리뷰 작성
 * GET    /reviews/product/{productId}  - 상품 리뷰 목록
 * GET    /reviews/user/{userId}        - 사용자 리뷰 목록
 * PUT    /reviews/{id}                 - 리뷰 수정
 * DELETE /reviews/{id}?userId=         - 리뷰 삭제
 */
@Tag(name = "Review", description = "상품 리뷰 및 평점 API")
@Validated
@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "리뷰 작성", description = "사용자가 특정 상품에 리뷰를 작성한다. 중복 작성은 불가.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "작성 성공"),
            @ApiResponse(responseCode = "409", description = "이미 작성된 리뷰 존재")
    })
    @PostMapping
    public ResponseEntity<?> createReview(@Valid @RequestBody ReviewRequest request) {
        try {
            Review review = reviewService.createReview(
                    request.getProductId(),
                    request.getUserId(),
                    request.getRating(),
                    request.getContent()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(new ReviewResponse(review));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @Operation(summary = "상품 리뷰 목록 조회", description = "특정 상품의 리뷰를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/product/{productId}")
    public ResponseEntity<List<ReviewResponse>> getProductReviews(
            @Parameter(description = "상품 ID", required = true) @PathVariable @Positive(message = "상품 ID는 양수여야 합니다") Long productId) {
        List<ReviewResponse> reviews = reviewService.getProductReviews(productId)
                .stream().map(ReviewResponse::new).collect(Collectors.toList());
        return ResponseEntity.ok(reviews);
    }

    @Operation(summary = "사용자 리뷰 목록 조회", description = "특정 사용자가 작성한 리뷰를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ReviewResponse>> getUserReviews(
            @Parameter(description = "사용자 ID", required = true) @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        List<ReviewResponse> reviews = reviewService.getUserReviews(userId)
                .stream().map(ReviewResponse::new).collect(Collectors.toList());
        return ResponseEntity.ok(reviews);
    }

    @Operation(summary = "리뷰 수정", description = "본인이 작성한 리뷰의 평점/내용을 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "403", description = "수정 권한 없음"),
            @ApiResponse(responseCode = "404", description = "리뷰를 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public ResponseEntity<?> updateReview(
            @Parameter(description = "리뷰 ID", required = true) @PathVariable @Positive(message = "리뷰 ID는 양수여야 합니다") Long id,
            @Valid @RequestBody ReviewUpdateRequest request) {
        try {
            Review updated = reviewService.updateReview(
                    id, request.getUserId(), request.getRating(), request.getContent());
            return ResponseEntity.ok(new ReviewResponse(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @Operation(summary = "리뷰 삭제", description = "본인이 작성한 리뷰를 삭제한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "403", description = "삭제 권한 없음"),
            @ApiResponse(responseCode = "404", description = "리뷰를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteReview(
            @Parameter(description = "리뷰 ID", required = true) @PathVariable @Positive(message = "리뷰 ID는 양수여야 합니다") Long id,
            @Parameter(description = "요청자(작성자) 사용자 ID", required = true) @RequestParam @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        try {
            reviewService.deleteReview(id, userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }
}
