package github.lms.lemuel.review.application;

import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewExport;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewPage;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewQuery;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewRow;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewStatusCount;
import github.lms.lemuel.review.application.port.out.LoadReviewPort;
import github.lms.lemuel.review.application.port.out.SaveReviewPort;
import github.lms.lemuel.review.application.port.out.SearchReviewsPort;
import github.lms.lemuel.review.domain.Review;
import github.lms.lemuel.review.domain.ReviewStatus;
import github.lms.lemuel.review.domain.exception.ReviewInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 리뷰 콘솔 서비스 단위 테스트.
 *
 * <p>조회 정규화(평점 상한 클램프·기간 경계)와 블라인드 조작 두 축을 함께 지킨다.
 * 특히 {@code maxRating=0} 이 그대로 넘어가면 목록이 영영 비어 운영자가 "신고가 없구나"로
 * 잘못 읽는다 — 조용히 틀리는 종류라 테스트로 못박는다.
 */
@ExtendWith(MockitoExtension.class)
class ReviewConsoleServiceTest {

    static final class RecordingSearchPort implements SearchReviewsPort {
        final List<ReviewCriteria> criteriaSeen = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        final List<Integer> pages = new ArrayList<>();
        long total;
        List<ReviewRow> rows = List.of();
        List<ReviewStatusCount> statusCounts = List.of();

        @Override
        public List<ReviewRow> search(ReviewCriteria criteria, int page, int size) {
            criteriaSeen.add(criteria);
            pages.add(page);
            sizes.add(size);
            return rows;
        }

        @Override
        public long count(ReviewCriteria criteria) {
            criteriaSeen.add(criteria);
            return total;
        }

        @Override
        public List<ReviewStatusCount> countByStatus(ReviewCriteria criteria) {
            criteriaSeen.add(criteria);
            return statusCounts;
        }
    }

    @Mock LoadReviewPort loadReviewPort;
    @Mock SaveReviewPort saveReviewPort;

    RecordingSearchPort searchPort;
    ReviewConsoleService service;

    @BeforeEach
    void setUp() {
        searchPort = new RecordingSearchPort();
        service = new ReviewConsoleService(searchPort, loadReviewPort, saveReviewPort);
    }

    private static ReviewQuery query(Integer maxRating, LocalDate from, LocalDate to, int page, int size) {
        return new ReviewQuery(null, null, null, null, maxRating, from, to, page, size);
    }

    private static ReviewRow row() {
        return new ReviewRow(1L, 2L, "상품", 3L, "a@b.c", 1, "내용", "VISIBLE", null, null, null,
                LocalDateTime.of(2026, 3, 1, 12, 0));
    }

    @Test
    @DisplayName("평점 상한 0 은 1 로 끌어올린다 — 그대로 두면 목록이 영영 비어 '신고 없음'으로 오독된다")
    void clampsMaxRatingFloor() {
        searchPort.total = 0;

        service.search(query(0, null, null, 0, 10));

        assertThat(searchPort.criteriaSeen.get(0).maxRating()).isEqualTo(1);
    }

    @Test
    @DisplayName("평점 상한 9 는 5 로 내린다")
    void clampsMaxRatingCeiling() {
        searchPort.total = 0;

        service.search(query(9, null, null, 0, 10));

        assertThat(searchPort.criteriaSeen.get(0).maxRating()).isEqualTo(5);
    }

    @Test
    @DisplayName("평점 상한을 안 주면 조건에서 뺀다 — 전 평점을 본다")
    void noRatingFilterWhenAbsent() {
        searchPort.total = 0;

        service.search(query(null, null, null, 0, 10));

        assertThat(searchPort.criteriaSeen.get(0).maxRating()).isNull();
    }

    @Test
    @DisplayName("작성일 종료일은 그날을 포함한다")
    void endDateIsInclusiveDay() {
        searchPort.total = 0;

        service.search(query(null, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 0, 10));

        SearchReviewsPort.ReviewCriteria criteria = searchPort.criteriaSeen.get(0);
        assertThat(criteria.from()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(criteria.toExclusive()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
    }

    @Test
    @DisplayName("뒤집힌 기간은 바로잡는다")
    void swapsInvertedRange() {
        searchPort.total = 0;

        service.search(query(null, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1), 0, 10));

        assertThat(searchPort.criteriaSeen.get(0).from())
                .isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
    }

    @Test
    @DisplayName("size 는 상한 200 으로 잘리고 0 이하는 50 이 된다")
    void clampsPageSize() {
        searchPort.total = 10_000;
        searchPort.rows = List.of(row());

        service.search(query(null, null, null, 0, 5_000));
        assertThat(searchPort.sizes.get(0)).isEqualTo(200);

        service.search(query(null, null, null, 0, 0));
        assertThat(searchPort.sizes.get(1)).isEqualTo(50);
    }

    @Test
    @DisplayName("총 건수 0 이면 목록 쿼리를 던지지 않는다")
    void skipsListQueryWhenEmpty() {
        searchPort.total = 0;

        ReviewPage result = service.search(query(null, null, null, 0, 10));

        assertThat(searchPort.sizes).isEmpty();
        assertThat(result.content()).isEmpty();
    }

    @Test
    @DisplayName("내보내기는 상한 5000 에서 끊고 잘렸다고 알린다")
    void exportTruncates() {
        searchPort.total = 12_345;
        List<ReviewRow> chunk = new ArrayList<>();
        for (int i = 0; i < 200; i++) chunk.add(row());
        searchPort.rows = chunk;

        ReviewExport export = service.export(query(null, null, null, 0, 10));

        assertThat(export.rows()).hasSize(5_000);
        assertThat(export.truncated()).isTrue();
    }

    @Test
    @DisplayName("블라인드는 도메인 규칙을 거쳐 저장된다")
    void hideDelegatesToDomainAndSaves() {
        Review review = Review.create(2L, 3L, 1, "욕설");
        when(loadReviewPort.findById(7L)).thenReturn(Optional.of(review));
        when(saveReviewPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Review result = service.hide(7L, "욕설 신고", 9L);

        assertThat(result.getStatus()).isEqualTo(ReviewStatus.HIDDEN);
        assertThat(result.getHiddenReason()).isEqualTo("욕설 신고");
        assertThat(result.getHiddenBy()).isEqualTo(9L);
        verify(saveReviewPort).save(review);
    }

    @Test
    @DisplayName("사유 없는 블라인드는 도메인이 막고 저장까지 가지 않는다")
    void hideWithoutReasonNeverSaves() {
        Review review = Review.create(2L, 3L, 1, "욕설");
        when(loadReviewPort.findById(7L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.hide(7L, " ", 9L))
                .isInstanceOf(ReviewInvariantViolationException.class);

        verify(saveReviewPort, never()).save(any());
    }

    @Test
    @DisplayName("해제는 숨김 근거를 지운다")
    void restoreClearsTrace() {
        Review review = Review.create(2L, 3L, 1, "욕설");
        review.hide("오판", 9L);
        when(loadReviewPort.findById(7L)).thenReturn(Optional.of(review));
        when(saveReviewPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Review result = service.restore(7L, 11L);

        assertThat(result.getStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(result.getHiddenReason()).isNull();
    }

    @Test
    @DisplayName("없는 리뷰를 숨기려 하면 거부한다")
    void hideMissingReview() {
        when(loadReviewPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.hide(99L, "사유", 9L))
                .isInstanceOf(ReviewInvariantViolationException.class);
    }

    @Test
    @DisplayName("상태별 집계도 같은 정규화를 거친다")
    void statusCountsShareNormalization() {
        searchPort.statusCounts = List.of(new ReviewStatusCount("HIDDEN", 3));

        List<ReviewStatusCount> counts = service.countByStatus(query(0, null, null, 0, 1));

        assertThat(counts).containsExactly(new ReviewStatusCount("HIDDEN", 3));
        assertThat(searchPort.criteriaSeen.get(0).maxRating()).isEqualTo(1);
    }
}
