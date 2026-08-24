package github.lms.lemuel.review.application.port.in;

import github.lms.lemuel.review.domain.ReviewStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 리뷰 관리 콘솔 조회 유스케이스.
 *
 * <p>공개 조회({@code /reviews/product/{id}})는 상품 하나의 목록이라 운영에는 쓸 수 없다.
 * 운영자가 필요한 것은 "어제 들어온 1점짜리 리뷰"나 "이 사용자가 쓴 글 전부"처럼
 * <b>상품을 가로지르는</b> 조회다.
 */
public interface SearchReviewsUseCase {

    /** 조건에 맞는 리뷰를 최신순 페이지로 조회한다. */
    ReviewPage search(ReviewQuery query);

    /** 같은 조건의 노출 상태별 건수. */
    List<ReviewStatusCount> countByStatus(ReviewQuery query);

    /** 같은 조건의 내보내기용 목록(상한 있음). */
    ReviewExport export(ReviewQuery query);

    /**
     * 조회 조건.
     *
     * @param keyword    본문 부분일치(대소문자 무시). 공백/null 이면 미적용
     * @param productId  상품 정확일치. null 이면 미적용
     * @param userId     작성자 정확일치. null 이면 미적용
     * @param status     노출 상태. null 이면 공개·블라인드를 함께 본다
     * @param maxRating  이 점수 <b>이하</b>만. 낮은 평점부터 훑는 것이 운영의 기본 동선이다
     * @param from       작성일 시작(포함). null 이면 미적용
     * @param to         작성일 종료(포함). null 이면 미적용
     */
    record ReviewQuery(
            String keyword,
            Long productId,
            Long userId,
            ReviewStatus status,
            Integer maxRating,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
    }

    /** 한 페이지. */
    record ReviewPage(
            List<ReviewRow> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    /** 목록 한 줄. */
    record ReviewRow(
            Long id,
            Long productId,
            String productName,
            Long userId,
            String userEmail,
            int rating,
            String content,
            String status,
            String hiddenReason,
            Long hiddenBy,
            LocalDateTime hiddenAt,
            LocalDateTime createdAt) {
    }

    /** 노출 상태별 건수. */
    record ReviewStatusCount(String status, long count) {
    }

    /** 내보내기 결과. */
    record ReviewExport(List<ReviewRow> rows, boolean truncated, long totalElements) {
    }
}
