package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardAccessPolicyTest {

    @Test
    @DisplayName("읽기 역할이 비면 공개 게시판 — 미인증(null·공백 역할)도 읽는다")
    void emptyReadRolesMeansPublic() {
        BoardAccessPolicy policy = BoardAccessPolicy.of(List.of(), List.of("ADMIN"), List.of("USER"), List.of("ADMIN"));

        assertThat(policy.isPublicRead()).isTrue();
        assertThat(policy.canRead(null)).isTrue();
        assertThat(policy.canRead("")).isTrue();
        assertThat(policy.canRead("GUEST")).isTrue();
    }

    @Test
    @DisplayName("읽기 역할을 지정하면 목록에 없는 역할은 막힌다")
    void restrictedRead() {
        BoardAccessPolicy policy = BoardAccessPolicy.of(
                List.of("ADMIN", "MANAGER"), List.of("ADMIN"), List.of("ADMIN"), List.of("ADMIN"));

        assertThat(policy.isPublicRead()).isFalse();
        assertThat(policy.canRead("MANAGER")).isTrue();
        assertThat(policy.canRead("USER")).isFalse();
        assertThat(policy.canRead(null)).isFalse();
    }

    @Test
    @DisplayName("역할은 대소문자·공백을 정규화해 비교한다 — 'user' 로 저장돼도 'USER' 로 판정된다")
    void normalizesRoles() {
        BoardAccessPolicy policy = BoardAccessPolicy.of(
                List.of(" admin "), List.of("user"), List.of("user"), List.of("admin"));

        assertThat(policy.canRead("ADMIN")).isTrue();
        assertThat(policy.canWrite(" USER ")).isTrue();
        assertThat(policy.readRoles()).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("null 원소·빈 문자열은 조용히 걸러진다")
    void ignoresBlankTokens() {
        BoardAccessPolicy policy = BoardAccessPolicy.of(
                Arrays.asList("ADMIN", null, "  "), List.of("ADMIN"), List.of("ADMIN"), List.of("ADMIN"));

        assertThat(policy.readRoles()).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("쓰기·댓글·운영 역할이 비면 거부한다 — 익명 쓰기는 지원하지 않는다")
    void writeCommentManageMustNotBeEmpty() {
        assertThatThrownBy(() -> BoardAccessPolicy.of(List.of(), List.of(), List.of("USER"), List.of("ADMIN")))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("쓰기");

        assertThatThrownBy(() -> BoardAccessPolicy.of(List.of(), List.of("USER"), List.of(), List.of("ADMIN")))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("댓글");

        assertThatThrownBy(() -> BoardAccessPolicy.of(List.of(), List.of("USER"), List.of("USER"), List.of()))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("운영");
    }

    @Test
    @DisplayName("null 컬렉션은 빈 집합과 같게 다룬다")
    void nullCollectionIsEmpty() {
        assertThatThrownBy(() -> BoardAccessPolicy.of(null, null, List.of("USER"), List.of("ADMIN")))
                .isInstanceOf(BoardInvariantViolationException.class);

        BoardAccessPolicy policy = BoardAccessPolicy.of(null, List.of("ADMIN"), List.of("ADMIN"), List.of("ADMIN"));
        assertThat(policy.isPublicRead()).isTrue();
    }

    @Test
    @DisplayName("rehydrate 는 재검증하지 않는다 — 정책 강화 후에도 기존 행을 읽을 수 있어야 한다")
    void rehydrateSkipsValidation() {
        BoardAccessPolicy policy = BoardAccessPolicy.rehydrate(Set.of(), Set.of(), Set.of(), Set.of());

        assertThat(policy.canWrite("ADMIN")).isFalse();
        assertThat(policy.writeRoles()).isEmpty();
        assertThat(policy.commentRoles()).isEmpty();
        assertThat(policy.manageRoles()).isEmpty();
    }
}
