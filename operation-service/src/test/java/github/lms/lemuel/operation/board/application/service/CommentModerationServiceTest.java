package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.application.port.in.ModeratedComment;
import github.lms.lemuel.operation.board.application.port.out.CommentSearchCriteria;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardCommentPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.operation.board.application.port.out.LoadCommentReportPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardCommentPort;
import github.lms.lemuel.operation.board.application.port.out.SaveCommentReportPort;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportReason;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardCommentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.CommentReportNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.DuplicateCommentReportException;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentModerationServiceTest {

    private static final Instant FIXED = Instant.parse("2026-08-27T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);

    private static final BoardActor ADMIN = BoardActor.of(99L, "ADMIN");
    private static final BoardActor REPORTER = BoardActor.of(11L, "USER");
    private static final BoardAuthor REPORTER_NAME = new BoardAuthor(11L, "re***");
    private static final BoardAuthor WRITER_NAME = new BoardAuthor(10L, "co***");

    @Mock
    private LoadBoardDefinitionPort loadBoardDefinitionPort;
    @Mock
    private LoadBoardPostPort loadBoardPostPort;
    @Mock
    private LoadBoardCommentPort loadBoardCommentPort;
    @Mock
    private SaveBoardCommentPort saveBoardCommentPort;
    @Mock
    private LoadCommentReportPort loadCommentReportPort;
    @Mock
    private SaveCommentReportPort saveCommentReportPort;

    private CommentModerationService service;

    @BeforeEach
    void setUp() {
        service = new CommentModerationService(loadBoardDefinitionPort, loadBoardPostPort,
                loadBoardCommentPort, saveBoardCommentPort, loadCommentReportPort, saveCommentReportPort,
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static BoardDefinition definition() {
        return BoardDefinition.rehydrate(1L, "notice", "공지", null, BoardSkin.LIST,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, true, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.rehydrate(List.of(), List.of("USER"), List.of("USER"), List.of("ADMIN")),
                true, NOW, NOW);
    }

    private static BoardComment comment(Long id, Long boardId, BoardCommentStatus status) {
        return BoardComment.rehydrate(id, 5L, boardId, null, WRITER_NAME, "문제의 댓글", status, NOW, NOW);
    }

    private static CommentReport report(Long id, CommentReportStatus status) {
        return CommentReport.rehydrate(id, 7L, REPORTER_NAME, CommentReportReason.ABUSE, null,
                status, status == CommentReportStatus.RECEIVED ? null : "ad***",
                status == CommentReportStatus.RECEIVED ? null : NOW, NOW);
    }

    @Test
    @DisplayName("신고 접수 — 저장되지만 댓글은 그대로다")
    void report() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 1L, BoardCommentStatus.PUBLISHED)));
        when(loadCommentReportPort.existsByCommentIdAndReporterId(7L, 11L)).thenReturn(false);
        when(saveCommentReportPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CommentReport saved = service.report("notice", 7L, REPORTER, REPORTER_NAME,
                CommentReportReason.ABUSE, "욕설입니다");

        assertThat(saved.getStatus()).isEqualTo(CommentReportStatus.RECEIVED);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        verify(saveBoardCommentPort, never()).save(any());
    }

    @Test
    @DisplayName("같은 사람의 두 번째 신고는 409 — 큐의 건수가 여론처럼 보이지 않게")
    void duplicateReport() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 1L, BoardCommentStatus.PUBLISHED)));
        when(loadCommentReportPort.existsByCommentIdAndReporterId(7L, 11L)).thenReturn(true);

        assertThatThrownBy(() -> service.report("notice", 7L, REPORTER, REPORTER_NAME,
                CommentReportReason.SPAM, null))
                .isInstanceOf(DuplicateCommentReportException.class);
        verify(saveCommentReportPort, never()).save(any());
    }

    @Test
    @DisplayName("다른 게시판의 댓글 식별자로는 신고할 수 없다")
    void reportCrossBoard() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition()));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 2L, BoardCommentStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.report("notice", 7L, REPORTER, REPORTER_NAME,
                CommentReportReason.SPAM, null))
                .isInstanceOf(BoardCommentNotFoundException.class);
    }

    @Test
    @DisplayName("통합 조회 — 게시판·글 제목·신고 건수를 한 줄에 붙인다")
    void searchEnrichesContext() {
        BoardComment row = comment(7L, 1L, BoardCommentStatus.PUBLISHED);
        when(loadBoardCommentPort.search(any(), anyInt(), anyInt()))
                .thenReturn(BoardPage.of(List.of(row), 0, 20, 1));
        when(loadBoardDefinitionPort.findAll()).thenReturn(List.of(definition()));
        when(loadBoardPostPort.findTitlesByIds(List.of(5L))).thenReturn(Map.of(5L, "문제의 글"));
        when(loadCommentReportPort.countByCommentIds(List.of(7L))).thenReturn(Map.of(7L, 3));

        BoardPage<ModeratedComment> page = service.search(
                new CommentSearchCriteria(null, null, null, null, false), 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        ModeratedComment first = page.content().get(0);
        assertThat(first.boardKey()).isEqualTo("notice");
        assertThat(first.boardName()).isEqualTo("공지");
        assertThat(first.postTitle()).isEqualTo("문제의 글");
        assertThat(first.reportCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("빈 결과에서는 맥락 조회를 아예 내보내지 않는다")
    void searchSkipsEnrichmentWhenEmpty() {
        when(loadBoardCommentPort.search(any(), anyInt(), anyInt()))
                .thenReturn(BoardPage.of(List.of(), 0, 20, 0));

        assertThat(service.search(new CommentSearchCriteria(null, null, null, null, true), 0, 20)
                .content()).isEmpty();
        verify(loadBoardDefinitionPort, never()).findAll();
        verify(loadCommentReportPort, never()).countByCommentIds(any());
    }

    @Test
    @DisplayName("가림은 게시판 운영 권한을 태운다 — 경로가 /admin 이라는 사실만으로 통과시키지 않는다")
    void hideRequiresBoardManageRole() {
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 1L, BoardCommentStatus.PUBLISHED)));
        when(loadBoardDefinitionPort.findById(1L)).thenReturn(Optional.of(definition()));

        assertThatThrownBy(() -> service.hide(7L, BoardActor.of(11L, "USER")))
                .isInstanceOf(BoardAccessDeniedException.class);
        verify(saveBoardCommentPort, never()).save(any());
    }

    @Test
    @DisplayName("HIDDEN 판정은 신고를 닫으면서 댓글도 내린다 — 원본이 놓친 바로 그 조치")
    void resolveHidesComment() {
        BoardComment target = comment(7L, 1L, BoardCommentStatus.PUBLISHED);
        when(loadCommentReportPort.findById(3L)).thenReturn(Optional.of(report(3L, CommentReportStatus.RECEIVED)));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(target));
        when(loadBoardDefinitionPort.findById(1L)).thenReturn(Optional.of(definition()));
        when(saveCommentReportPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CommentReport resolved = service.resolve(3L, CommentReportStatus.HIDDEN, ADMIN, "ad***");

        assertThat(target.getStatus()).isEqualTo(BoardCommentStatus.HIDDEN);
        assertThat(resolved.getStatus()).isEqualTo(CommentReportStatus.HIDDEN);
        assertThat(resolved.getHandledBy()).isEqualTo("ad***");
        assertThat(resolved.getHandledAt()).isEqualTo(NOW);
        verify(saveBoardCommentPort).save(target);
    }

    @Test
    @DisplayName("KEPT 판정은 댓글을 건드리지 않는다")
    void resolveKeepsComment() {
        when(loadCommentReportPort.findById(3L)).thenReturn(Optional.of(report(3L, CommentReportStatus.RECEIVED)));
        when(saveCommentReportPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.resolve(3L, CommentReportStatus.KEPT, ADMIN, "ad***").getStatus())
                .isEqualTo(CommentReportStatus.KEPT);
        verify(loadBoardCommentPort, never()).findById(any());
        verify(saveBoardCommentPort, never()).save(any());
    }

    @Test
    @DisplayName("이미 가려진 댓글의 두 번째 신고도 판정은 닫힌다 — 큐에 영영 안 닫히는 건이 남지 않게")
    void resolveOnAlreadyHiddenComment() {
        when(loadCommentReportPort.findById(4L)).thenReturn(Optional.of(report(4L, CommentReportStatus.RECEIVED)));
        when(loadBoardCommentPort.findById(7L)).thenReturn(Optional.of(comment(7L, 1L, BoardCommentStatus.HIDDEN)));
        when(saveCommentReportPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.resolve(4L, CommentReportStatus.HIDDEN, ADMIN, "ad***").getStatus())
                .isEqualTo(CommentReportStatus.HIDDEN);
        verify(saveBoardCommentPort, never()).save(any());
    }

    @Test
    @DisplayName("없는 신고는 404")
    void resolveMissingReport() {
        when(loadCommentReportPort.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(9L, CommentReportStatus.KEPT, ADMIN, "ad***"))
                .isInstanceOf(CommentReportNotFoundException.class);
    }
}
