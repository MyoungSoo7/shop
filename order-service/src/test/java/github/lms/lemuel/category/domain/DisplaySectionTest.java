package github.lms.lemuel.category.domain;

import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;
import github.lms.lemuel.category.domain.exception.InvalidCategoryStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DisplaySection — 진열 편성")
class DisplaySectionTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 8, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 8, 20, 0, 0);

    private static DisplaySection exhibition(LocalDateTime startsAt, LocalDateTime endsAt) {
        return DisplaySection.create("EXH_SUMMER", "여름 기획전",
                DisplaySectionKind.EXHIBITION, null, startsAt, endsAt, 0);
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("기간 없는 편성은 상시 진열이다")
        void openEndedIsAlwaysVisible() {
            DisplaySection section = exhibition(null, null);

            assertThat(section.isVisibleAt(T0)).isTrue();
            assertThat(section.isVisibleAt(T2)).isTrue();
        }

        @Test
        @DisplayName("CATEGORY_BEST 는 대상 카테고리가 필수다")
        void categoryBestRequiresCategory() {
            assertThatThrownBy(() -> DisplaySection.create("BEST", "베스트",
                    DisplaySectionKind.CATEGORY_BEST, null, null, null, 0))
                    .isInstanceOf(CategoryInvariantViolationException.class)
                    .hasMessageContaining("카테고리");

            assertThat(DisplaySection.create("BEST", "베스트",
                    DisplaySectionKind.CATEGORY_BEST, 7L, null, null, 0).getCategoryId())
                    .isEqualTo(7L);
        }

        @Test
        @DisplayName("카테고리를 지목하지 않는 종류는 넘어온 카테고리를 버린다")
        void nonTargetingKindDropsCategory() {
            assertThat(DisplaySection.create("MAIN_TOP", "메인",
                    DisplaySectionKind.MAIN, 7L, null, null, 0).getCategoryId())
                    .isNull();
        }

        @Test
        @DisplayName("종료가 시작보다 앞서면 거부한다")
        void rejectsInvertedPeriod() {
            assertThatThrownBy(() -> exhibition(T2, T1))
                    .isInstanceOf(CategoryInvariantViolationException.class)
                    .hasMessageContaining("종료");
        }

        @Test
        @DisplayName("시작과 종료가 같아도 거부한다 (경계)")
        void rejectsZeroLengthPeriod() {
            assertThatThrownBy(() -> exhibition(T1, T1))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }

        @ParameterizedTest(name = "[{index}] 잘못된 코드: \"{0}\"")
        @ValueSource(strings = {"", "  ", "a", "lower", "A", "1START", "WITH SPACE", "WITH-DASH"})
        @DisplayName("코드는 대문자로 시작하는 2자 이상 영문 대문자·숫자·밑줄이어야 한다")
        void rejectsInvalidCode(String code) {
            assertThatThrownBy(() -> DisplaySection.create(code, "이름",
                    DisplaySectionKind.MAIN, null, null, null, 0))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }

        @Test
        @DisplayName("이름 200자는 허용, 201자는 거부한다 (경계)")
        void enforcesNameBoundary() {
            assertThat(DisplaySection.create("MAIN_TOP", "가".repeat(200),
                    DisplaySectionKind.MAIN, null, null, null, 0).getName()).hasSize(200);

            assertThatThrownBy(() -> DisplaySection.create("MAIN_TOP", "가".repeat(201),
                    DisplaySectionKind.MAIN, null, null, null, 0))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }

        @Test
        @DisplayName("음수 정렬 순서는 거부한다")
        void rejectsNegativeSortOrder() {
            assertThatThrownBy(() -> DisplaySection.create("MAIN_TOP", "메인",
                    DisplaySectionKind.MAIN, null, null, null, -1))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("노출 판정")
    class Visibility {

        @Test
        @DisplayName("시작 전에는 보이지 않고, 시작 시각부터 보인다 (경계)")
        void startBoundary() {
            DisplaySection section = exhibition(T1, T2);

            assertThat(section.isVisibleAt(T1.minusSeconds(1))).isFalse();
            assertThat(section.isVisibleAt(T1)).isTrue();
        }

        @Test
        @DisplayName("종료 시각에는 이미 보이지 않는다 (경계)")
        void endBoundary() {
            DisplaySection section = exhibition(T1, T2);

            assertThat(section.isVisibleAt(T2.minusSeconds(1))).isTrue();
            assertThat(section.isVisibleAt(T2)).isFalse();
        }

        @Test
        @DisplayName("비활성이면 기간 안이어도 보이지 않는다 — 플래그와 기간을 모두 만족해야 한다")
        void inactiveHidesRegardlessOfPeriod() {
            DisplaySection section = exhibition(T1, T2);
            section.deactivate();

            assertThat(section.isVisibleAt(T1.plusDays(1))).isFalse();

            section.activate();
            assertThat(section.isVisibleAt(T1.plusDays(1))).isTrue();
        }

        @Test
        @DisplayName("기간을 다시 잡으면 끝난 편성도 되살아난다")
        void rescheduleRevives() {
            DisplaySection section = exhibition(T0, T1);
            assertThat(section.isVisibleAt(T2)).isFalse();

            section.reschedule(T1, T2.plusDays(1));

            assertThat(section.isVisibleAt(T2)).isTrue();
        }

        @Test
        @DisplayName("뒤집힌 기간으로는 다시 잡을 수 없다")
        void rescheduleRejectsInverted() {
            DisplaySection section = exhibition(T0, T2);

            assertThatThrownBy(() -> section.reschedule(T2, T1))
                    .isInstanceOf(CategoryInvariantViolationException.class);
            assertThat(section.getEndsAt()).isEqualTo(T2);
        }

        @Test
        @DisplayName("판정 시각 null 은 거부한다")
        void rejectsNullInstant() {
            assertThatThrownBy(() -> exhibition(null, null).isVisibleAt(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("대상 변경")
    class Retarget {

        @Test
        @DisplayName("CATEGORY_BEST 만 카테고리를 바꿀 수 있다")
        void onlyCategoryBestCanRetarget() {
            DisplaySection best = DisplaySection.create("BEST", "베스트",
                    DisplaySectionKind.CATEGORY_BEST, 1L, null, null, 0);
            best.retarget(2L);
            assertThat(best.getCategoryId()).isEqualTo(2L);

            DisplaySection main = DisplaySection.create("MAIN_TOP", "메인",
                    DisplaySectionKind.MAIN, null, null, null, 0);
            assertThatThrownBy(() -> main.retarget(2L))
                    .isInstanceOf(InvalidCategoryStateException.class);
        }

        @Test
        @DisplayName("대상 카테고리를 비울 수는 없다")
        void rejectsNullTarget() {
            DisplaySection best = DisplaySection.create("BEST", "베스트",
                    DisplaySectionKind.CATEGORY_BEST, 1L, null, null, 0);

            assertThatThrownBy(() -> best.retarget(null))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("편성 항목")
    class Items {

        @Test
        @DisplayName("고정 상품이 정렬 순서보다 앞선다")
        void pinnedComesFirst() {
            List<DisplaySectionItem> items = new java.util.ArrayList<>(List.of(
                    DisplaySectionItem.of(1L, 10L, 0, false),
                    DisplaySectionItem.of(1L, 20L, 5, true),
                    DisplaySectionItem.of(1L, 30L, 1, false)));

            items.sort(DisplaySectionItem.DISPLAY_ORDER);

            assertThat(items).extracting(DisplaySectionItem::getProductId)
                    .containsExactly(20L, 10L, 30L);
        }

        @Test
        @DisplayName("고정 해제하면 정렬 순서대로 돌아간다")
        void unpinRestoresOrder() {
            DisplaySectionItem pinned = DisplaySectionItem.of(1L, 20L, 5, true);
            DisplaySectionItem plain = DisplaySectionItem.of(1L, 10L, 0, false);

            assertThat(pinned.displayOrder()).isLessThan(plain.displayOrder());

            pinned.unpin();

            assertThat(pinned.displayOrder()).isGreaterThan(plain.displayOrder());
        }

        @Test
        @DisplayName("같은 편성의 같은 상품은 하나로 취급한다")
        void identityBySectionAndProduct() {
            assertThat(DisplaySectionItem.of(1L, 10L, 0, false))
                    .isEqualTo(DisplaySectionItem.of(1L, 10L, 9, true))
                    .isNotEqualTo(DisplaySectionItem.of(2L, 10L, 0, false));
        }

        @Test
        @DisplayName("음수 정렬 순서는 거부한다")
        void rejectsNegativeSortOrder() {
            assertThatThrownBy(() -> DisplaySectionItem.of(1L, 10L, -1, false))
                    .isInstanceOf(CategoryInvariantViolationException.class);

            DisplaySectionItem item = DisplaySectionItem.of(1L, 10L, 0, false);
            assertThatThrownBy(() -> item.changeSortOrder(-1))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }

        @Test
        @DisplayName("참조 null 은 거부한다")
        void rejectsNullReferences() {
            assertThatThrownBy(() -> DisplaySectionItem.of(null, 10L, 0, false))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> DisplaySectionItem.of(1L, null, 0, false))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("고정·정렬을 바꿀 수 있다")
        void mutations() {
            DisplaySectionItem item = DisplaySectionItem.of(1L, 10L, 0, false);

            item.pin();
            item.changeSortOrder(3);

            assertThat(item.isPinned()).isTrue();
            assertThat(item.getSortOrder()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("식별자·표시")
    class Identity {

        @Test
        @DisplayName("rehydrate 로 복원하고 id 는 1 회만 부여한다")
        void identity() {
            DisplaySection restored = DisplaySection.rehydrate(3L, "MAIN_TOP", "메인",
                    DisplaySectionKind.MAIN, null, null, null, 2, false);
            assertThat(restored.getId()).isEqualTo(3L);
            assertThat(restored.isActive()).isFalse();

            DisplaySection created = exhibition(null, null);
            created.assignId(9L);
            assertThatThrownBy(() -> created.assignId(10L))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("이름·정렬을 바꿀 수 있고 잘못된 값은 거부한다")
        void mutations() {
            DisplaySection section = exhibition(null, null);

            section.rename("가을 기획전");
            section.changeSortOrder(3);

            assertThat(section.getName()).isEqualTo("가을 기획전");
            assertThat(section.getSortOrder()).isEqualTo(3);
            assertThatThrownBy(() -> section.rename(" "))
                    .isInstanceOf(CategoryInvariantViolationException.class);
            assertThatThrownBy(() -> section.changeSortOrder(-1))
                    .isInstanceOf(CategoryInvariantViolationException.class);
        }

        @Test
        @DisplayName("CATEGORY_BEST 만 카테고리를 요구한다")
        void kindRequiresCategory() {
            assertThat(DisplaySectionKind.CATEGORY_BEST.requiresCategory()).isTrue();
            assertThat(DisplaySectionKind.MAIN.requiresCategory()).isFalse();
            assertThat(DisplaySectionKind.EXHIBITION.requiresCategory()).isFalse();
        }
    }
}
