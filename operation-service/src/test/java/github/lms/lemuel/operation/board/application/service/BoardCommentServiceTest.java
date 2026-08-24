package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.out.LoadBoardCommentPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardCommentPort;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.BoardPostStatus;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import github.lms.lemuel.operation.board.domain.exception.BoardCommentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardPostNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardCommentServiceTest {

    private static final Instant FIXED = Instant.parse("2026-08-15T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);

    private static final BoardActor COMMENTER = BoardActor.of(10L, "USER");
    private static final BoardAuthor COMMENTER_NAME = new BoardAuthor(10L, "co***");

    @Mock
    private LoadBoardDefinitionPort loadBoardDefinitionPort;
    @Mock
    private LoadBoardPostPort loadBoardPostPort;
    @Mock
    private LoadBoardCommentPort loadBoardCommentPort;
    @Mock
    private SaveBoardCommentPort saveBoardCommentPort;

    private BoardCommentService service;

    @BeforeEach
    void setUp() {
        service = new BoardCommentService(loadBoardDefinitionPort, loadBoardPostPort,
                loadBoardCommentPort, saveBoardCommentPort, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static BoardDefinition definition() {
        return BoardDefinition.rehydrate(1L, "notice", "공지", null, BoardSkin.LIST,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, true, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.rehydrate(List.of(), List.of("USER"), List.of("USER"), List.of("ADMIN")),
                true, NOW, NOW);
    }

    private static BoardPost post(Long id, Long boardId, boolean secret, BoardPostStatus status) {
        return BoardPost.rehydrate(id, boardId, null, "제목", "본문", BoardContentFormat.TEXT,
                new BoardAuthor(5L, "wr***"), false, secret, status, 0L, NOW, NOW);
    }

    private static BoardComment comment(Long id, Long postId, Long boardId, Long parentId) {
        return BoardComment.rehydrate(id, postId, boardId, parentId, COMMENTER_NAME,
                "내용", BoardCommentStatus.PUBLISHED, NOW, NOW);
    }

    @Test
    @DisplayName("볼 수 없는 글의 댓글은 404 — 비밀글의 댓글 수도 새지 않는다")
    void invisiblePostHidesComments() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 1L, true, BoardPostStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.listByPost("notice", 5L, BoardActor.of(11L, "USER")))
                .isInstanceOf(BoardPostNotFoundException.class);
        verify(loadBoardCommentPort, never()).findByPostId(any());
    }

    @Test
    @DisplayName("다른 게시판의 글 식별자는 404")
    void postFromAnotherBoard() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 2L, false, BoardPostStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.listByPost("notice", 5L, COMMENTER))
                .isInstanceOf(BoardPostNotFoundException.class);
    }

    @Test
    @DisplayName("댓글 목록은 작성순으로 그대로 돌려준다")
    void listByPost() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 1L, false, BoardPostStatus.PUBLISHED)));
        when(loadBoardCommentPort.findByPostId(5L)).thenReturn(List.of(comment(1L, 5L, 1L, null)));

        assertThat(service.listByPost("notice", 5L, BoardActor.anonymous())).hasSize(1);
    }

    @Test
    @DisplayName("작성은 도메인에 위임하고 저장한다")
    void create() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 1L, false, BoardPostStatus.PUBLISHED)));
        when(saveBoardCommentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardComment created = service.create("notice", 5L, COMMENTER, COMMENTER_NAME, "댓글", null);

        assertThat(created.getPostId()).isEqualTo(5L);
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("다른 글의 댓글을 부모로 지정하면 404")
    void parentFromAnotherPost() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 1L, false, BoardPostStatus.PUBLISHED)));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 6L, 1L, null)));

        assertThatThrownBy(() -> service.create("notice", 5L, COMMENTER, COMMENTER_NAME, "답글", 7L))
                .isInstanceOf(BoardCommentNotFoundException.class);
    }

    @Test
    @DisplayName("답글은 부모를 불러와 도메인에 넘긴다")
    void createReply() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 1L, false, BoardPostStatus.PUBLISHED)));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 5L, 1L, null)));
        when(saveBoardCommentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardComment reply = service.create("notice", 5L, COMMENTER, COMMENTER_NAME, "답글", 7L);

        assertThat(reply.getParentId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("다른 게시판의 댓글 식별자로는 지울 수 없다")
    void deleteFromAnotherBoard() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 5L, 2L, null)));

        assertThatThrownBy(() -> service.delete("notice", 7L, COMMENTER))
                .isInstanceOf(BoardCommentNotFoundException.class);
        verify(saveBoardCommentPort, never()).save(any());
    }

    @Test
    @DisplayName("없는 댓글 삭제는 404")
    void deleteMissing() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("notice", 7L, COMMENTER))
                .isInstanceOf(BoardCommentNotFoundException.class);
    }

    @Test
    @DisplayName("작성자는 자기 댓글을 지운다")
    void delete() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 5L, 1L, null)));
        when(saveBoardCommentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.delete("notice", 7L, COMMENTER);

        verify(saveBoardCommentPort).save(any(BoardComment.class));
    }
}
