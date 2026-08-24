package github.lms.lemuel.review.application.port.out;

import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewRow;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewStatusCount;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 리뷰 콘솔 조회 포트.
 *
 * <p>기간은 이미 정규화된 반개구간({@code from} 이상 {@code toExclusive} 미만)으로 받는다.
 * "종료일 포함"의 해석은 정책이라 서비스가 정하고, 어댑터는 경계 계산을 다시 하지 않는다.
 */
public interface SearchReviewsPort {

    /** 조건에 맞는 리뷰를 작성 최신순으로 한 페이지 조회한다. */
    List<ReviewRow> search(ReviewCriteria criteria, int page, int size);

    /** 같은 조건의 총 건수. */
    long count(ReviewCriteria criteria);

    /** 같은 조건의 노출 상태별 건수. */
    List<ReviewStatusCount> countByStatus(ReviewCriteria criteria);

    /** 정규화된 조회 조건. 값이 null 이면 그 조건은 적용하지 않는다. */
    record ReviewCriteria(
            String keyword,
            Long productId,
            Long userId,
            String status,
            Integer maxRating,
            LocalDateTime from,
            LocalDateTime toExclusive) {
    }
}
