package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardContentPolicyTest {

    @Test
    @DisplayName("분류 코드그룹은 대문자로 정규화된다 — 공통코드 쪽 저장 규칙과 같게 접어야 매칭이 된다")
    void normalizesCategoryGroupCode() {
        BoardContentPolicy policy = BoardContentPolicy.of(
                BoardContentFormat.MARKDOWN, true, false, " board_cat_notice ");

        assertThat(policy.categoryGroupCode()).isEqualTo("BOARD_CAT_NOTICE");
        assertThat(policy.hasCategoryGroup()).isTrue();
    }

    @Test
    @DisplayName("분류 코드그룹이 없으면 null 로 접히고 hasCategoryGroup 이 거짓이다")
    void noCategoryGroup() {
        assertThat(BoardContentPolicy.of(BoardContentFormat.TEXT, true, false, null).hasCategoryGroup()).isFalse();
        assertThat(BoardContentPolicy.of(BoardContentFormat.TEXT, true, false, "  ").categoryGroupCode()).isNull();
    }

    @ParameterizedTest
    @DisplayName("분류 코드그룹 형식이 아니면 거부한다")
    @ValueSource(strings = {"board-cat", "게시판분류", "board cat"})
    void rejectsMalformedGroupCode(String groupCode) {
        assertThatThrownBy(() -> BoardContentPolicy.of(BoardContentFormat.TEXT, true, false, groupCode))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("분류");
    }

    @Test
    @DisplayName("분류 코드그룹 40자는 허용하고 41자는 거부한다 — 경계")
    void groupCodeLengthBoundary() {
        assertThat(BoardContentPolicy.of(BoardContentFormat.TEXT, true, false, "A".repeat(40)).categoryGroupCode())
                .hasSize(40);

        assertThatThrownBy(() -> BoardContentPolicy.of(BoardContentFormat.TEXT, true, false, "A".repeat(41)))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("본문 형식이 null 이면 거부한다")
    void rejectsNullFormat() {
        assertThatThrownBy(() -> BoardContentPolicy.of(null, true, false, null))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("HTML 게시판만 sanitize 를 요구한다")
    void onlyHtmlRequiresSanitize() {
        assertThat(BoardContentPolicy.of(BoardContentFormat.HTML, true, false, null).requiresSanitize()).isTrue();
        assertThat(BoardContentPolicy.of(BoardContentFormat.TEXT, true, false, null).requiresSanitize()).isFalse();
        assertThat(BoardContentPolicy.of(BoardContentFormat.MARKDOWN, true, false, null).requiresSanitize()).isFalse();
    }

    @Test
    @DisplayName("댓글·비밀글 플래그는 그대로 보존된다")
    void flagsPreserved() {
        BoardContentPolicy policy = BoardContentPolicy.of(BoardContentFormat.TEXT, false, true, null);

        assertThat(policy.isCommentsEnabled()).isFalse();
        assertThat(policy.isSecretEnabled()).isTrue();
        assertThat(policy.contentFormat()).isEqualTo(BoardContentFormat.TEXT);
    }

    @Test
    @DisplayName("rehydrate 는 재검증하지 않는다")
    void rehydrateSkipsValidation() {
        BoardContentPolicy policy = BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, true, "legacy-code");

        assertThat(policy.categoryGroupCode()).isEqualTo("legacy-code");
    }
}
