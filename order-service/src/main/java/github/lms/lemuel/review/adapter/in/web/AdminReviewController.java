package github.lms.lemuel.review.adapter.in.web;

import github.lms.lemuel.common.web.csv.CsvResponse;
import github.lms.lemuel.review.application.port.in.ModerateReviewUseCase;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewExport;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewPage;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewQuery;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewRow;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewStatusCount;
import github.lms.lemuel.review.domain.Review;
import github.lms.lemuel.review.domain.ReviewStatus;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 리뷰 관리 콘솔.
 *
 * <pre>
 *   GET  /admin/reviews                  → 조건 검색(작성 최신순 페이지)
 *   GET  /admin/reviews/status-counts    → 같은 조건의 노출 상태별 건수
 *   GET  /admin/reviews/statuses         → 필터 드롭다운용 상태 목록
 *   GET  /admin/reviews/export           → 같은 조건의 CSV
 *   POST /admin/reviews/{id}/hide        → 블라인드(사유 필수)
 *   POST /admin/reviews/{id}/restore     → 블라인드 해제
 * </pre>
 *
 * <p><b>왜 필요한가</b>: 지금까지 리뷰를 내리는 유일한 방법은 <b>작성자 본인의 삭제</b>였다.
 * 욕설·개인정보 노출·경쟁사 도배가 올라와도 운영자가 할 수 있는 일은 DB 를 직접 손대는
 * 것뿐이었고, 그렇게 지운 글은 근거도 복구 경로도 남지 않는다.
 *
 * <p><b>삭제 엔드포인트를 두지 않은 이유</b>: 운영자에게 필요한 것은 노출 차단이지 말소가
 * 아니다. 지울 수 있는 버튼이 있으면 언젠가 눌리고, 그 뒤에는 이의 제기에 답할 원문이 없다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/reviews/**} 매처로 ADMIN·MANAGER 에 제한된다.
 * 회원 콘솔과 달리 MANAGER 를 포함하는 이유는, 이 표면이 다루는 것이 개인정보가 아니라
 * <b>공개된 게시물</b>이고 신고 대응은 CS 업무의 일부이기 때문이다.
 */
@Tag(name = "Admin Review", description = "리뷰 검색 · 블라인드")
@RestController
@RequestMapping("/admin/reviews")
@RequiredArgsConstructor
public class AdminReviewController {

    private final SearchReviewsUseCase searchReviewsUseCase;
    private final ModerateReviewUseCase moderateReviewUseCase;
    private final LoadUserPort loadUserPort;

    @GetMapping
    @Operation(summary = "리뷰 검색", description = "본문·상품·작성자·상태·평점상한·작성일로 좁혀 최신순 조회")
    public ResponseEntity<ReviewPage> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(searchReviewsUseCase.search(
                toQuery(keyword, productId, userId, status, maxRating, from, to, page, size)));
    }

    @GetMapping("/status-counts")
    @Operation(summary = "노출 상태별 건수", description = "블라인드가 몇 건인지는 목록을 세어 알 일이 아니다")
    public ResponseEntity<List<ReviewStatusCount>> statusCounts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        // 상태별 집계에 상태 필터를 걸면 고른 상태 하나만 남아 집계의 의미가 사라진다.
        return ResponseEntity.ok(searchReviewsUseCase.countByStatus(
                toQuery(keyword, productId, userId, null, maxRating, from, to, 0, 1)));
    }

    @GetMapping("/statuses")
    @Operation(summary = "노출 상태 목록", description = "필터 드롭다운용 — 서버 enum 이 정본이다")
    public ResponseEntity<List<String>> statuses() {
        return ResponseEntity.ok(Arrays.stream(ReviewStatus.values()).map(Enum::name).toList());
    }

    @GetMapping("/export")
    @Operation(summary = "리뷰 CSV", description = "화면과 같은 조건으로 최대 5000행")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        ReviewExport exported = searchReviewsUseCase.export(
                toQuery(keyword, productId, userId, status, maxRating, from, to, 0, 1));

        ResponseEntity<ByteArrayResource> csv = CsvResponse.of(
                "reviews",
                List.of("ID", "상품", "작성자", "평점", "내용", "상태", "블라인드사유", "작성일시"),
                exported.rows(),
                AdminReviewController::toCells);

        return ResponseEntity.status(csv.getStatusCode())
                .headers(csv.getHeaders())
                .header("X-Export-Truncated", String.valueOf(exported.truncated()))
                .header("X-Export-Total", String.valueOf(exported.totalElements()))
                .body(csv.getBody());
    }

    @PostMapping("/{reviewId}/hide")
    @Operation(summary = "리뷰 블라인드", description = "원문은 남기고 공개 목록에서만 뺀다. 사유 필수")
    public ResponseEntity<ModerationResponse> hide(@PathVariable Long reviewId,
                                                   @Valid @RequestBody HideRequest request) {
        Review review = moderateReviewUseCase.hide(reviewId, request.reason(), currentUserId());
        return ResponseEntity.ok(ModerationResponse.from(review));
    }

    @PostMapping("/{reviewId}/restore")
    @Operation(summary = "블라인드 해제", description = "다시 공개한다")
    public ResponseEntity<ModerationResponse> restore(@PathVariable Long reviewId) {
        Review review = moderateReviewUseCase.restore(reviewId, currentUserId());
        return ResponseEntity.ok(ModerationResponse.from(review));
    }

    /**
     * JWT 주체(이메일)로 조작자를 해석한다.
     *
     * <p>요청 본문으로 받지 않는 이유: 조작자를 요청이 정하게 두면 "누가 숨겼는가"가 위조
     * 가능해지고, 작성자 이의 제기에 답할 근거가 사라진다.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        return loadUserPort.findByEmail(authentication.getName()).map(User::getId).orElse(null);
    }

    private static List<String> toCells(ReviewRow row) {
        return List.of(
                Objects.toString(row.id(), ""),
                Objects.toString(row.productName(), ""),
                Objects.toString(row.userEmail(), ""),
                String.valueOf(row.rating()),
                Objects.toString(row.content(), ""),
                Objects.toString(row.status(), ""),
                Objects.toString(row.hiddenReason(), ""),
                Objects.toString(row.createdAt(), ""));
    }

    /** 모르는 상태 이름은 필터 미적용으로 흘린다 — 오타 하나가 목록을 통째로 비우면 안 된다. */
    private static ReviewQuery toQuery(String keyword, Long productId, Long userId, String status,
                                       Integer maxRating, LocalDate from, LocalDate to,
                                       int page, int size) {
        ReviewStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = ReviewStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                parsed = null;
            }
        }
        return new ReviewQuery(keyword, productId, userId, parsed, maxRating, from, to, page, size);
    }

    /** 블라인드 요청. 사유는 비울 수 없다 — 작성자에게도 감사에도 설명할 수 없게 된다. */
    public record HideRequest(@NotBlank String reason) {
    }

    /** 조작 결과 — 화면이 목록을 다시 읽기 전 즉시 상태를 반영할 수 있게 한다. */
    public record ModerationResponse(Long id, String status, String hiddenReason, Long hiddenBy) {
        static ModerationResponse from(Review review) {
            return new ModerationResponse(review.getId(), review.getStatus().name(),
                    review.getHiddenReason(), review.getHiddenBy());
        }
    }
}
