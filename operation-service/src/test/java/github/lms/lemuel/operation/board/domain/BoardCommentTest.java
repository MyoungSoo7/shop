package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardCommentTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-15T09:00:00Z");

    private static final BoardActor COMMENTER = BoardActor.of(10L, "USER");
    private static final BoardAuthor COMMENTER_NAME = new BoardAuthor(10L, "co***");
    private static final BoardActor STRANGER = BoardActor.of(11L, "USER");
    private static final BoardActor MANAGER = BoardActor.of(99L, "ADMIN");

    private static BoardDefinition board(boolean commentsEnabled) {
        return BoardDefinition.create("notice", "공지", null, BoardSkin.LIST,
                BoardContentPolicy.of(BoardContentFormat.TEXT, commentsEnabled, false, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.of(List.of(), List.of("USER", "ADMIN"), List.of("USER"), List.of("ADMIN")),
                NOW);
    }

    private static BoardPost post(BoardDefinition definition) {
        return BoardPost.rehydrate(1L, 1L, null, "제목", "본문", BoardContentFormat.TEXT,
                new BoardAuthor(5L, "wr***"), false, false, BoardPostStatus.PUBLISHED, 0L, NOW, NOW);
    }

    private static BoardComment comment(BoardDefinition definition, BoardPost post) {
        return BoardComment.create(definition, post, COMMENTER, COMMENTER_NAME, "댓글 내용", null, NOW);
    }

    @Test
    @DisplayName("정상 작성 — 글·게시판 식별자를 함께 든다")
    void create() {
        BoardDefinition definition = board(true);
        BoardComment created = comment(definition, post(definition));

        assertThat(created.getPostId()).isEqualTo(1L);
        assertThat(created.getBoardId()).isEqualTo(1L);
        assertThat(created.getParentId()).isNull();
        assertThat(created.getStatus()).isEqualTo(BoardCommentStatus.PUBLISHED);
        assertThat(created.getAuthor().displayName()).isEqualTo("co***");
    }

    @Test
    @DisplayName("댓글이 꺼진 게시판에서는 역할이 맞아도 쓸 수 없다")
    void commentsDisabled() {
        BoardDefinition definition = board(false);

        assertThatThrownBy(() -> comment(definition, post(definition)))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    @DisplayName("댓글 권한이 없는 역할·미인증은 거부한다")
    void deniesWithoutPermission() {
        BoardDefinition definition = board(true);
        BoardPost post = post(definition);

        assertThatThrownBy(() -> BoardComment.create(definition, post, BoardActor.of(20L, "GUEST"),
                new BoardAuthor(20L, "gu***"), "댓글", null, NOW))
                .isInstanceOf(BoardAccessDeniedException.class);

        assertThatThrownBy(() -> BoardComment.create(definition, post, BoardActor.anonymous(),
                COMMENTER_NAME, "댓글", null, NOW))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    @DisplayName("주체와 작성자 식별자가 다르면 거부한다")
    void deniesAuthorSpoofing() {
        BoardDefinition definition = board(true);

        assertThatThrownBy(() -> BoardComment.create(definition, post(definition), COMMENTER,
                new BoardAuthor(11L, "st***"), "댓글", null, NOW))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    @DisplayName("노출 상태가 아닌 글에는 댓글을 달 수 없다")
    void deniesOnUnpublishedPost() {
        BoardDefinition definition = board(true);
        BoardPost hidden = BoardPost.rehydrate(1L, 1L, null, "제목", "본문", BoardContentFormat.TEXT,
                new BoardAuthor(5L, "wr***"), false, false, BoardPostStatus.HIDDEN, 0L, NOW, NOW);

        assertThatThrownBy(() -> BoardComment.create(definition, hidden, COMMENTER, COMMENTER_NAME,
                "댓글", null, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("내용 2000자는 허용하고 2001자·공백은 거부한다 — 경계")
    void contentBoundary() {
        BoardDefinition definition = board(true);
        BoardPost post = post(definition);

        assertThatCode(() -> BoardComment.create(definition, post, COMMENTER, COMMENTER_NAME,
                "가".repeat(2000), null, NOW)).doesNotThrowAnyException();

        assertThatThrownBy(() -> BoardComment.create(definition, post, COMMENTER, COMMENTER_NAME,
                "가".repeat(2001), null, NOW)).isInstanceOf(BoardInvariantViolationException.class);
        assertThatThrownBy(() -> BoardComment.create(definition, post, COMMENTER, COMMENTER_NAME,
                "  ", null, NOW)).isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("대댓글은 1단까지 — 답글의 답글은 거부한다")
    void replyDepthLimited() {
        BoardDefinition definition = board(true);
        BoardPost post = post(definition);
        BoardComment parent = BoardComment.rehydrate(7L, 1L, 1L, null, new BoardAuthor(5L, "wr***"),
                "부모", BoardCommentStatus.PUBLISHED, NOW, NOW);

        BoardComment reply = BoardComment.create(definition, post, COMMENTER, COMMENTER_NAME, "답글", parent, NOW);
        assertThat(reply.getParentId()).isEqualTo(7L);

        assertThatThrownBy(() -> BoardComment.create(definition, post, COMMENTER, COMMENTER_NAME,
                "답글의 답글", reply, NOW))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("답글");
    }

    @Test
    @DisplayName("다른 글의 댓글에는 답글을 달 수 없다")
    void replyMustBelongToSamePost() {
        BoardDefinition definition = board(true);
        BoardComment otherPostComment = BoardComment.rehydrate(7L, 2L, 1L, null,
                new BoardAuthor(5L, "wr***"), "남의 글 댓글", BoardCommentStatus.PUBLISHED, NOW, NOW);

        assertThatThrownBy(() -> BoardComment.create(definition, post(definition), COMMENTER, COMMENTER_NAME,
                "답글", otherPostComment, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("지워진 댓글에는 답글을 달 수 없다")
    void cannotReplyToDeleted() {
        BoardDefinition definition = board(true);
        BoardComment deleted = BoardComment.rehydrate(7L, 1L, 1L, null, new BoardAuthor(5L, "wr***"),
                "지워진 댓글", BoardCommentStatus.DELETED, NOW, NOW);

        assertThatThrownBy(() -> BoardComment.create(definition, post(definition), COMMENTER, COMMENTER_NAME,
                "답글", deleted, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("삭제는 작성자와 운영 역할만, 남은 지울 수 없다")
    void softDelete() {
        BoardDefinition definition = board(true);
        BoardComment created = comment(definition, post(definition));

        assertThatThrownBy(() -> created.softDelete(STRANGER, definition, NOW))
                .isInstanceOf(BoardAccessDeniedException.class);

        created.softDelete(COMMENTER, definition, NOW);
        assertThat(created.getStatus()).isEqualTo(BoardCommentStatus.DELETED);

        assertThatThrownBy(() -> created.softDelete(COMMENTER, definition, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("운영 역할은 남의 댓글도 지운다")
    void managerDeletes() {
        BoardDefinition definition = board(true);
        BoardComment created = comment(definition, post(definition));

        assertThatCode(() -> created.softDelete(MANAGER, definition, NOW)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("지워진 댓글은 내용을 감추되 자리는 남긴다 — 대화의 앞말이 사라지지 않게")
    void deletedKeepsPlaceholder() {
        BoardDefinition definition = board(true);
        BoardComment created = comment(definition, post(definition));
        created.softDelete(COMMENTER, definition, NOW);

        assertThat(created.visibleContent()).isEqualTo("삭제된 댓글입니다.");
        assertThat(created.getContent()).isEqualTo("댓글 내용");
    }

    @Test
    @DisplayName("가림은 운영 역할만 — 작성자 본인도 못 가린다(신고 처리의 결과이므로)")
    void hideRequiresManager() {
        BoardDefinition definition = board(true);
        BoardComment created = comment(definition, post(definition));

        assertThatThrownBy(() -> created.hide(COMMENTER, definition, NOW))
                .isInstanceOf(BoardAccessDeniedException.class);
        assertThatThrownBy(() -> created.hide(STRANGER, definition, NOW))
                .isInstanceOf(BoardAccessDeniedException.class);

        created.hide(MANAGER, definition, NOW);
        assertThat(created.getStatus()).isEqualTo(BoardCommentStatus.HIDDEN);
    }

    @Test
    @DisplayName("가림은 되돌릴 수 있다 — 되돌릴 수 없는 조치는 운영이 쓰기를 꺼린다")
    void hideIsReversible() {
        BoardDefinition definition = board(true);
        BoardComment created = comment(definition, post(definition));

        created.hide(MANAGER, definition, NOW);
        assertThat(created.visibleContent()).isEqualTo("신고 처리로 가려진 댓글입니다.");
        // 원문은 남는다 — 판정 근거를 보려면 관리 콘솔이 이걸 읽어야 한다.
        assertThat(created.getContent()).isEqualTo("댓글 내용");

        created.unhide(MANAGER, definition, NOW);
        assertThat(created.getStatus()).isEqualTo(BoardCommentStatus.PUBLISHED);
        assertThat(created.visibleContent()).isEqualTo("댓글 내용");
    }

    @Test
    @DisplayName("이미 가려졌거나 지워진 댓글은 다시 가리지 않는다")
    void hideGuards() {
        BoardDefinition definition = board(true);
        BoardComment hidden = comment(definition, post(definition));
        hidden.hide(MANAGER, definition, NOW);
        assertThatThrownBy(() -> hidden.hide(MANAGER, definition, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);

        BoardComment deleted = comment(definition, post(definition));
        deleted.softDelete(COMMENTER, definition, NOW);
        assertThatThrownBy(() -> deleted.hide(MANAGER, definition, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("가려지지 않은 댓글은 되돌릴 것이 없다")
    void unhideGuards() {
        BoardDefinition definition = board(true);
        BoardComment created = comment(definition, post(definition));

        assertThatThrownBy(() -> created.unhide(MANAGER, definition, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }

    @Test
    @DisplayName("가려진 댓글에도 답글을 달 수 없다 — 내용이 안 보이는 말에 대화를 잇지 않는다")
    void cannotReplyToHidden() {
        BoardDefinition definition = board(true);
        BoardComment hidden = BoardComment.rehydrate(7L, 1L, 1L, null, new BoardAuthor(5L, "wr***"),
                "가려진 댓글", BoardCommentStatus.HIDDEN, NOW, NOW);

        assertThatThrownBy(() -> BoardComment.create(definition, post(definition), COMMENTER, COMMENTER_NAME,
                "답글", hidden, NOW))
                .isInstanceOf(BoardInvariantViolationException.class);
    }
}
