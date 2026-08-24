package github.lms.lemuel.review.application;

import github.lms.lemuel.review.application.port.in.ModerateReviewUseCase;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase;
import github.lms.lemuel.review.application.port.out.LoadReviewPort;
import github.lms.lemuel.review.application.port.out.SaveReviewPort;
import github.lms.lemuel.review.application.port.out.SearchReviewsPort;
import github.lms.lemuel.review.application.port.out.SearchReviewsPort.ReviewCriteria;
import github.lms.lemuel.review.domain.Review;
import github.lms.lemuel.review.domain.exception.ReviewInvariantViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 리뷰 관리 콘솔 서비스 — 조회 정규화와 블라인드 조작.
 *
 * <p><b>기존 {@link ReviewService} 와 나눈 이유</b>: 저쪽은 작성자 관점(내 리뷰 쓰기·고치기·
 * 지우기)이고 소유권 검사가 규칙의 중심이다. 여기는 운영자 관점이라 소유권 검사가 <b>없어야</b>
 * 한다. 두 규칙을 한 클래스에 두면 언젠가 한쪽 조건이 다른 쪽에 새어 들어간다.
 *
 * <p>평점 필터를 "이하"로 두는 이유: 운영자가 훑는 것은 늘 낮은 평점이다. "1점만"이 아니라
 * "2점 이하"처럼 폭을 넓혀 보는 것이 실제 동선이라 상한 하나로 받는다.
 */
@Service
@RequiredArgsConstructor
public class ReviewConsoleService implements SearchReviewsUseCase, ModerateReviewUseCase {

    /** 한 페이지 최대 건수. 리뷰 본문이 통째로 실리므로 상한이 없으면 응답이 폭주한다. */
    public static final int MAX_PAGE_SIZE = 200;

    /** 한 페이지 기본 건수. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** CSV 내보내기 최대 행수. 넘치면 잘라내되 잘렸다는 사실을 함께 돌려준다. */
    public static final int MAX_EXPORT_ROWS = 5_000;

    private final SearchReviewsPort searchReviewsPort;
    private final LoadReviewPort loadReviewPort;
    private final SaveReviewPort saveReviewPort;

    @Override
    @Transactional(readOnly = true)
    public ReviewPage search(ReviewQuery query) {
        ReviewCriteria criteria = toCriteria(query);
        int page = Math.max(query.page(), 0);
        int size = normalizeSize(query.size());

        long total = searchReviewsPort.count(criteria);
        List<ReviewRow> content = total == 0
                ? List.of()
                : searchReviewsPort.search(criteria, page, size);

        int totalPages = (int) ((total + size - 1) / size);
        return new ReviewPage(content, page, size, total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewStatusCount> countByStatus(ReviewQuery query) {
        return searchReviewsPort.countByStatus(toCriteria(query));
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewExport export(ReviewQuery query) {
        ReviewCriteria criteria = toCriteria(query);
        long total = searchReviewsPort.count(criteria);
        if (total == 0) {
            return new ReviewExport(List.of(), false, 0);
        }

        int wanted = (int) Math.min(total, MAX_EXPORT_ROWS);
        List<ReviewRow> rows = new ArrayList<>(wanted);
        for (int page = 0; rows.size() < wanted; page++) {
            List<ReviewRow> chunk = searchReviewsPort.search(criteria, page, MAX_PAGE_SIZE);
            if (chunk.isEmpty()) {
                break;
            }
            for (ReviewRow row : chunk) {
                if (rows.size() == wanted) {
                    break;
                }
                rows.add(row);
            }
        }

        return new ReviewExport(rows, total > MAX_EXPORT_ROWS, total);
    }

    @Override
    @Transactional
    public Review hide(Long reviewId, String reason, Long actorId) {
        Review review = require(reviewId);
        review.hide(reason, actorId);
        return saveReviewPort.save(review);
    }

    @Override
    @Transactional
    public Review restore(Long reviewId, Long actorId) {
        Review review = require(reviewId);
        review.restore(actorId);
        return saveReviewPort.save(review);
    }

    private Review require(Long reviewId) {
        return loadReviewPort.findById(reviewId)
                .orElseThrow(() -> new ReviewInvariantViolationException(
                        "리뷰를 찾을 수 없습니다. id=" + reviewId));
    }

    /**
     * 화면 질의를 어댑터 조건으로 옮긴다.
     *
     * <p>뒤집힌 기간은 거부하지 않고 바로잡는다. 평점 상한은 1~5 로 클램프한다 —
     * {@code maxRating=0} 이 그대로 넘어가면 조건에 맞는 리뷰가 영영 없고, 운영자는
     * "신고가 없구나"로 잘못 읽는다.
     */
    private ReviewCriteria toCriteria(ReviewQuery query) {
        LocalDate from = query.from();
        LocalDate to = query.to();
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }

        Integer maxRating = query.maxRating() == null
                ? null
                : Math.clamp(query.maxRating(), 1, 5);

        return new ReviewCriteria(
                blankToNull(query.keyword()),
                query.productId(),
                query.userId(),
                query.status() != null ? query.status().name() : null,
                maxRating,
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null);
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
