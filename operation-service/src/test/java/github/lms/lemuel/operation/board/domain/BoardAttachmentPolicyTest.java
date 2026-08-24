package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardAttachmentPolicyTest {

    @Test
    @DisplayName("비활성 정책은 0/0/빈집합으로 정규화된다 — '꺼졌는데 최대 5개' 같은 값이 남지 않는다")
    void disabledIsCanonical() {
        BoardAttachmentPolicy policy = BoardAttachmentPolicy.disabled();

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.maxCount()).isZero();
        assertThat(policy.maxSizeKb()).isZero();
        assertThat(policy.allowedExtensions()).isEmpty();
        assertThat(policy.permits("jpg")).isFalse();
    }

    @Test
    @DisplayName("활성 정책은 확장자를 소문자·점 제거로 정규화한다")
    void normalizesExtensions() {
        BoardAttachmentPolicy policy = BoardAttachmentPolicy.enabled(3, 1024, List.of(".JPG", "png", " .WebP "));

        assertThat(policy.allowedExtensions()).containsExactlyInAnyOrder("jpg", "png", "webp");
        assertThat(policy.permits(".JPG")).isTrue();
        assertThat(policy.permits("png")).isTrue();
        assertThat(policy.permits("exe")).isFalse();
        assertThat(policy.permits(null)).isFalse();
        assertThat(policy.permits("  ")).isFalse();
    }

    @Test
    @DisplayName("개수는 1~20 — 경계 밖은 거부한다")
    void countBoundary() {
        assertThatCode(() -> BoardAttachmentPolicy.enabled(1, 1, List.of("jpg"))).doesNotThrowAnyException();
        assertThatCode(() -> BoardAttachmentPolicy.enabled(20, 1, List.of("jpg"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> BoardAttachmentPolicy.enabled(0, 1024, List.of("jpg")))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("개수");
        assertThatThrownBy(() -> BoardAttachmentPolicy.enabled(21, 1024, List.of("jpg")))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("크기는 1~20480KB — 경계 밖은 거부한다")
    void sizeBoundary() {
        assertThatCode(() -> BoardAttachmentPolicy.enabled(1, 20_480, List.of("jpg"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> BoardAttachmentPolicy.enabled(1, 0, List.of("jpg")))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("크기");
        assertThatThrownBy(() -> BoardAttachmentPolicy.enabled(1, 20_481, List.of("jpg")))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("확장자를 하나도 주지 않으면 거부한다 — 전체 허용은 지원하지 않는다")
    void requiresAtLeastOneExtension() {
        assertThatThrownBy(() -> BoardAttachmentPolicy.enabled(1, 1024, List.of()))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("확장자");

        assertThatThrownBy(() -> BoardAttachmentPolicy.enabled(1, 1024, null))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @ParameterizedTest
    @DisplayName("확장자 형식이 아니면 거부한다")
    @ValueSource(strings = {"j pg", "jp.g", "이미지", "toolongextension", "jp/g", "-"})
    void rejectsMalformedExtension(String extension) {
        assertThatThrownBy(() -> BoardAttachmentPolicy.enabled(1, 1024, List.of(extension)))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("확장자");
    }

    @Test
    @DisplayName("rehydrate 는 재검증하지 않는다")
    void rehydrateSkipsValidation() {
        BoardAttachmentPolicy policy = BoardAttachmentPolicy.rehydrate(true, 99, 99_999, Set.of("jpg"));

        assertThat(policy.maxCount()).isEqualTo(99);
        assertThat(policy.permits("jpg")).isTrue();
    }
}
