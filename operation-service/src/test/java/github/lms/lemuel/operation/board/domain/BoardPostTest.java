package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 게시글 도메인 테스트.
 *
 * <p>이 클래스가 지키는 것은 두 가지다: ① 정의(게시판 정책)가 글을 실제로 구속하는가,
 * ② <b>인가가 도메인 안에 있는가</b>. 소유권 대조를 컨트롤러에 두면 어댑터를 하나 더 만들 때
 * 조용히 빠지고, 그게 IDOR 이 된다.
 */
class BoardPostTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-15T09:00:00Z");

    private static final BoardActor AUTHOR_ACTOR = BoardActor.of(10L, "USER");
    private static final BoardAuthor AUTHOR = new BoardAuthor(10L, "au***");
    private static final BoardActor STRANGER = BoardActor.of(11L, "USER");
    private static final BoardActor MANAGER = BoardActor.of(99L, "ADMIN");

    private static BoardDefinition board(boolean secretEnabled, boolean active, String categoryGroup) {
        BoardDefinition definition = BoardDefinition.create("notice", "공지", null, BoardSkin.LIST,
                BoardContentPolicy.of(BoardContentFormat.MARKDOWN, true, secretEnabled, categoryGroup),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.of(List.of(), List.of("USER", "ADMIN"), List.of("USER"), List.of("ADMIN")),
                NOW);
        if (!active) {
            definition.deactivate(NOW);
        }
        return definition;
    }

    private static BoardDefinition board() {
        return board(false, true, null);
    }

    private static BoardPost post(BoardDefinition definition) {
        return BoardPost.create(definition, AUTHOR_ACTOR, AUTHOR, "제목", "본문", null, false, NOW);
    }

    @Nested
    @DisplayName("작성")
    class Create {

        @Test
        @DisplayName("정상 작성 — 본문 형식은 게시판 정의에서 스냅샷된다")
        void createsWithSnapshotFormat() {
            BoardPost created = post(board());

            assertThat(created.getStatus()).isEqualTo(BoardPostStatus.PUBLISHED);
            assertThat(created.getContentFormat()).isEqualTo(BoardContentFormat.MARKDOWN);
            assertThat(created.getViewCount()).isZero();
            assertThat(created.isPinned()).isFalse();
            assertThat(created.getAuthor().displayName()).isEqualTo("au***");
            assertThat(created.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("쓰기 권한이 없는 역할은 거부한다")
        void deniesRoleWithoutWrite() {
            BoardActor guest = BoardActor.of(20L, "GUEST");

            assertThatThrownBy(() -> BoardPost.create(board(), guest, new BoardAuthor(20L, "gu***"),
                    "제목", "본문", null, false, NOW))
                    .isInstanceOf(BoardAccessDeniedException.class);
        }

        @Test
        @DisplayName("미인증 주체는 거부한다 — 익명 쓰기는 지원하지 않는다")
        void deniesAnonymous() {
            assertThatThrownBy(() -> BoardPost.create(board(), BoardActor.anonymous(), AUTHOR,
                    "제목", "본문", null, false, NOW))
                    .isInstanceOf(BoardAccessDeniedException.class);
        }

        @Test
        @DisplayName("주체와 작성자 식별자가 다르면 거부한다 — 내부 경로로도 남의 이름을 달 수 없다")
        void deniesAuthorSpoofing() {
            assertThatThrownBy(() -> BoardPost.create(board(), AUTHOR_ACTOR, new BoardAuthor(11L, "st***"),
                    "제목", "본문", null, false, NOW))
                    .isInstanceOf(BoardAccessDeniedException.class);
        }

        @Test
        @DisplayName("닫힌 게시판에는 쓸 수 없다")
        void deniesInactiveBoard() {
            assertThatThrownBy(() -> post(board(false, false, null)))
                    .isInstanceOf(BoardInvariantViolationException.class)
                    .hasMessageContaining("닫힌");
        }

        @Test
        @DisplayName("제목 200자는 허용하고 201자·공백은 거부한다 — 경계")
        void titleBoundary() {
            BoardDefinition definition = board();
            assertThatCode(() -> BoardPost.create(definition, AUTHOR_ACTOR, AUTHOR,
                    "가".repeat(200), "본문", null, false, NOW)).doesNotThrowAnyException();

            assertThatThrownBy(() -> BoardPost.create(definition, AUTHOR_ACTOR, AUTHOR,
                    "가".repeat(201), "본문", null, false, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
            assertThatThrownBy(() -> BoardPost.create(definition, AUTHOR_ACTOR, AUTHOR,
                    "   ", "본문", null, false, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("본문이 비면 거부한다")
        void blankContentRejected() {
            assertThatThrownBy(() -> BoardPost.create(board(), AUTHOR_ACTOR, AUTHOR,
                    "제목", "  ", null, false, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("비밀글을 허용하지 않는 게시판에 비밀글을 쓰면 거부한다")
        void secretNotAllowed() {
            assertThatThrownBy(() -> BoardPost.create(board(false, true, null), AUTHOR_ACTOR, AUTHOR,
                    "제목", "본문", null, true, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class)
                    .hasMessageContaining("비밀글");
        }

        @Test
        @DisplayName("비밀글을 허용하는 게시판에서는 통과한다")
        void secretAllowed() {
            assertThatCode(() -> BoardPost.create(board(true, true, null), AUTHOR_ACTOR, AUTHOR,
                    "제목", "본문", null, true, NOW)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("분류 그룹이 없는 게시판에 분류를 붙이면 거부한다")
        void categoryWithoutGroup() {
            assertThatThrownBy(() -> BoardPost.create(board(), AUTHOR_ACTOR, AUTHOR,
                    "제목", "본문", "URGENT", false, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class)
                    .hasMessageContaining("분류");
        }

        @Test
        @DisplayName("분류 그룹이 있으면 분류 코드는 대문자로 정규화된다")
        void categoryNormalized() {
            BoardPost created = BoardPost.create(board(false, true, "BOARD_CAT_NOTICE"), AUTHOR_ACTOR, AUTHOR,
                    "제목", "본문", " urgent ", false, NOW);

            assertThat(created.getCategoryCode()).isEqualTo("URGENT");
        }
    }

    @Nested
    @DisplayName("가시성")
    class Visibility {

        @Test
        @DisplayName("공개 글은 미인증도 볼 수 있다")
        void publicPost() {
            BoardPost created = post(board());

            assertThat(created.isVisibleTo(BoardActor.anonymous(), board())).isTrue();
            assertThat(created.isVisibleTo(STRANGER, board())).isTrue();
        }

        @Test
        @DisplayName("비밀글은 작성자와 운영 역할만 볼 수 있다")
        void secretPost() {
            BoardDefinition definition = board(true, true, null);
            BoardPost secret = BoardPost.create(definition, AUTHOR_ACTOR, AUTHOR, "제목", "본문", null, true, NOW);

            assertThat(secret.isVisibleTo(AUTHOR_ACTOR, definition)).isTrue();
            assertThat(secret.isVisibleTo(MANAGER, definition)).isTrue();
            assertThat(secret.isVisibleTo(STRANGER, definition)).isFalse();
            assertThat(secret.isVisibleTo(BoardActor.anonymous(), definition)).isFalse();
        }

        @Test
        @DisplayName("숨긴 글은 작성자에게도 안 보이고 운영 역할만 본다")
        void hiddenPost() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);
            created.hide(MANAGER, definition, NOW);

            assertThat(created.isVisibleTo(AUTHOR_ACTOR, definition)).isFalse();
            assertThat(created.isVisibleTo(MANAGER, definition)).isTrue();
        }

        @Test
        @DisplayName("지운 글은 운영 역할에게도 안 보인다")
        void deletedPost() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);
            created.softDelete(AUTHOR_ACTOR, definition, NOW);

            assertThat(created.isVisibleTo(AUTHOR_ACTOR, definition)).isFalse();
            assertThat(created.isVisibleTo(MANAGER, definition)).isFalse();
        }

        @Test
        @DisplayName("읽기 제한 게시판이면 역할이 맞아야 보인다")
        void restrictedRead() {
            BoardDefinition restricted = BoardDefinition.create("internal", "내부", null, BoardSkin.LIST,
                    BoardContentPolicy.of(BoardContentFormat.TEXT, true, false, null),
                    BoardAttachmentPolicy.disabled(),
                    BoardAccessPolicy.of(List.of("ADMIN"), List.of("ADMIN"), List.of("ADMIN"), List.of("ADMIN")),
                    NOW);
            BoardPost created = BoardPost.create(restricted, MANAGER, new BoardAuthor(99L, "ad***"),
                    "제목", "본문", null, false, NOW);

            assertThat(created.isVisibleTo(MANAGER, restricted)).isTrue();
            assertThat(created.isVisibleTo(STRANGER, restricted)).isFalse();
        }
    }

    @Nested
    @DisplayName("수정·삭제 (IDOR)")
    class Modify {

        @Test
        @DisplayName("작성자는 자기 글을 수정한다")
        void authorEdits() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);
            OffsetDateTime later = NOW.plusHours(1);

            created.edit(AUTHOR_ACTOR, definition, "새 제목", "새 본문", null, false, later);

            assertThat(created.getTitle()).isEqualTo("새 제목");
            assertThat(created.getUpdatedAt()).isEqualTo(later);
        }

        @Test
        @DisplayName("남의 글은 수정할 수 없다 — 소유권 대조는 도메인이 한다")
        void strangerCannotEdit() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);

            assertThatThrownBy(() -> created.edit(STRANGER, definition, "탈취", "탈취", null, false, NOW))
                    .isInstanceOf(BoardAccessDeniedException.class);
            assertThat(created.getTitle()).isEqualTo("제목");
        }

        @Test
        @DisplayName("운영 역할은 남의 글도 수정할 수 있다")
        void managerCanEdit() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);

            assertThatCode(() -> created.edit(MANAGER, definition, "정정", "정정", null, false, NOW))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("수정도 작성과 같은 불변식을 강제하고, 실패 시 아무것도 바뀌지 않는다")
        void editEnforcesInvariants() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);

            assertThatThrownBy(() -> created.edit(AUTHOR_ACTOR, definition, "  ", "본문", null, false, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
            assertThatThrownBy(() -> created.edit(AUTHOR_ACTOR, definition, "제목", "본문", null, true, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);

            assertThat(created.getTitle()).isEqualTo("제목");
            assertThat(created.isSecret()).isFalse();
        }

        @Test
        @DisplayName("지운 글은 수정할 수 없다")
        void deletedCannotBeEdited() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);
            created.softDelete(AUTHOR_ACTOR, definition, NOW);

            assertThatThrownBy(() -> created.edit(AUTHOR_ACTOR, definition, "부활", "부활", null, false, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("삭제는 물리 삭제가 아니라 상태 전이다")
        void softDelete() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);

            created.softDelete(AUTHOR_ACTOR, definition, NOW);

            assertThat(created.getStatus()).isEqualTo(BoardPostStatus.DELETED);
            assertThatThrownBy(() -> created.softDelete(AUTHOR_ACTOR, definition, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("남의 글은 지울 수 없다")
        void strangerCannotDelete() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);

            assertThatThrownBy(() -> created.softDelete(STRANGER, definition, NOW))
                    .isInstanceOf(BoardAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("운영 조작")
    class Moderation {

        @Test
        @DisplayName("고정은 운영 역할만 — 작성자라도 자기 글을 상단 고정할 수 없다")
        void pinRequiresManage() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);

            assertThatThrownBy(() -> created.changePinned(AUTHOR_ACTOR, definition, true, NOW))
                    .isInstanceOf(BoardAccessDeniedException.class);

            created.changePinned(MANAGER, definition, true, NOW);
            assertThat(created.isPinned()).isTrue();
        }

        @Test
        @DisplayName("숨김은 운영 역할만이고 되돌릴 수 있다")
        void hideAndRestore() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);

            assertThatThrownBy(() -> created.hide(AUTHOR_ACTOR, definition, NOW))
                    .isInstanceOf(BoardAccessDeniedException.class);

            created.hide(MANAGER, definition, NOW);
            assertThat(created.getStatus()).isEqualTo(BoardPostStatus.HIDDEN);

            created.restore(MANAGER, definition, NOW);
            assertThat(created.getStatus()).isEqualTo(BoardPostStatus.PUBLISHED);
        }

        @Test
        @DisplayName("지운 글은 숨기거나 되돌릴 수 없다 — 삭제는 종단이다")
        void deletedIsTerminal() {
            BoardDefinition definition = board();
            BoardPost created = post(definition);
            created.softDelete(AUTHOR_ACTOR, definition, NOW);

            assertThatThrownBy(() -> created.hide(MANAGER, definition, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
            assertThatThrownBy(() -> created.restore(MANAGER, definition, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("조회수는 누적된다")
        void viewCount() {
            BoardPost created = post(board());

            created.increaseView();
            created.increaseView();

            assertThat(created.getViewCount()).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("rehydrate 는 저장값을 그대로 복원한다")
    void rehydrate() {
        BoardPost restored = BoardPost.rehydrate(5L, 1L, "URGENT", "옛 제목", "옛 본문",
                BoardContentFormat.TEXT, new BoardAuthor(10L, "au***"), true, true,
                BoardPostStatus.HIDDEN, 42L, NOW, NOW);

        assertThat(restored.getId()).isEqualTo(5L);
        assertThat(restored.getBoardId()).isEqualTo(1L);
        assertThat(restored.getViewCount()).isEqualTo(42L);
        assertThat(restored.getStatus()).isEqualTo(BoardPostStatus.HIDDEN);
    }
}
