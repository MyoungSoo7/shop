package github.lms.lemuel.category.domain;

import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryProductCountDriftTest {

    @Test
    @DisplayName("캐시가 크면 과다 — 매핑을 지운 뒤 갱신이 빠진 쪽이다")
    void overcount() {
        CategoryProductCountDrift drift =
                CategoryProductCountDrift.of(1L, "shoes", "신발", 12, 9);

        assertThat(drift.kind()).isEqualTo(CategoryCountDriftKind.OVERCOUNT);
        assertThat(drift.difference()).isEqualTo(3);
    }

    @Test
    @DisplayName("캐시가 작으면 과소 — 새 상품이 트리에서 안 보인다")
    void undercount() {
        CategoryProductCountDrift drift =
                CategoryProductCountDrift.of(2L, "bags", "가방", 4, 7);

        assertThat(drift.kind()).isEqualTo(CategoryCountDriftKind.UNDERCOUNT);
        assertThat(drift.difference()).isEqualTo(-3);
    }

    @Test
    @DisplayName("같은 값으로는 만들 수 없다 — 정상 행이 섞이면 건수가 거짓이 된다")
    void equalCountsAreNotDrift() {
        assertThatThrownBy(() -> CategoryProductCountDrift.of(3L, "hats", "모자", 5, 5))
                .isInstanceOf(CategoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("음수 상품수는 드리프트가 아니라 데이터 손상이다")
    void negativeCountsRejected() {
        assertThatThrownBy(() -> CategoryProductCountDrift.of(4L, "x", "x", -1, 3))
                .isInstanceOf(CategoryInvariantViolationException.class);
        assertThatThrownBy(() -> CategoryProductCountDrift.of(4L, "x", "x", 3, -1))
                .isInstanceOf(CategoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("카테고리 id 가 없으면 어디를 고쳐야 할지 알 수 없다")
    void categoryIdRequired() {
        assertThatThrownBy(() -> CategoryProductCountDrift.of(null, "x", "x", 1, 2))
                .isInstanceOf(CategoryInvariantViolationException.class);
    }
}
