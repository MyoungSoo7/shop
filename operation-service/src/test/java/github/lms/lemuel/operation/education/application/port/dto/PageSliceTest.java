package github.lms.lemuel.operation.education.application.port.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageSliceTest {

    @Test
    void totalPagesRoundsUp() {
        assertThat(new PageSlice<>(List.of("a"), 0, 20, 41L).totalPages()).isEqualTo(3);
        assertThat(new PageSlice<>(List.of("a"), 0, 20, 40L).totalPages()).isEqualTo(2);
        assertThat(new PageSlice<>(List.of(), 0, 20, 0L).totalPages()).isZero();
    }

    @Test
    void emptySliceKeepsTheRequestedWindow() {
        PageSlice<String> empty = PageSlice.empty(new PageSpec(2, 50));

        assertThat(empty.content()).isEmpty();
        assertThat(empty.page()).isEqualTo(2);
        assertThat(empty.size()).isEqualTo(50);
        assertThat(empty.totalElements()).isZero();
    }

    @Test
    void contentIsDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("a"));
        PageSlice<String> slice = new PageSlice<>(mutable, 0, 20, 1L);

        mutable.add("b");

        assertThat(slice.content()).containsExactly("a");
    }

    @Test
    void pageSpecRejectsImpossibleWindows() {
        assertThatThrownBy(() -> new PageSpec(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageSpec(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
