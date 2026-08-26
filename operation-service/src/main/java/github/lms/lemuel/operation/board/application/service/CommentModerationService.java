package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.application.port.in.CommentModerationUseCase;
import github.lms.lemuel.operation.board.application.port.in.ModeratedComment;
import github.lms.lemuel.operation.board.application.port.out.CommentSearchCriteria;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardCommentPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.operation.board.application.port.out.LoadCommentReportPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardCommentPort;
import github.lms.lemuel.operation.board.application.port.out.SaveCommentReportPort;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportReason;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import github.lms.lemuel.operation.board.domain.exception.BoardCommentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.CommentReportNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.DuplicateCommentReportException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 댓글 통합 관리 응용 서비스.
 *
 * <p>다른 게시판 경로와 달리 조회가 <b>글을 거치지 않는다</b>. 신고된 댓글을 내리려면 그 댓글이
 * 달린 글을 관리자가 먼저 찾아내야 했던 것이 원래의 빈 구멍이었고, 그 구멍은 "댓글을 댓글로
 * 찾을 수 있는 경로"가 없어서 생긴 것이기 때문이다.
 *
 * <p>대신 조치({@link #hide}·{@link #unhide})는 게시판 운영 권한을 그대로 태운다 — 경로가
 * {@code /admin/**} 이라는 사실만으로 아무 게시판이나 손대게 두지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentModerationService implements CommentModerationUseCase {

    private final LoadBoardDefinitionPort loadBoardDefinitionPort;
    private final LoadBoardPostPort loadBoardPostPort;
    private final LoadBoardCommentPort loadBoardCommentPort;
    private final SaveBoardCommentPort saveBoardCommentPort;
    private final LoadCommentReportPort loadCommentReportPort;
    private final SaveCommentReportPort saveCommentReportPort;
    private final Clock clock;

    @Override
    @Transactional
    public CommentReport report(String boardKey, Long commentId, BoardActor actor, BoardAuthor reporter,
                                CommentReportReason reason, String detail) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardComment comment = commentOfBoard(definition, commentId);

        // 같은 사람이 같은 댓글을 여러 번 신고하면 큐의 건수가 여론처럼 보인다. DB 의 유니크
        // 인덱스가 최종 방어선이고, 여기서는 그걸 409 로 번역해 준다.
        if (loadCommentReportPort.existsByCommentIdAndReporterId(commentId, reporter.userId())) {
            throw new DuplicateCommentReportException(commentId);
        }

        CommentReport report = CommentReport.receive(comment, actor, reporter, reason, detail, now());
        return saveCommentReportPort.save(report);
    }

    @Override
    public BoardPage<ModeratedComment> search(CommentSearchCriteria criteria, int page, int size) {
        BoardPage<BoardComment> comments = loadBoardCommentPort.search(criteria, page, size);
        if (comments.content().isEmpty()) {
            return BoardPage.of(List.of(), page, size, comments.totalElements());
        }

        // 한 화면의 맥락을 왕복 세 번으로 채운다 — 줄마다 조회하면 20줄에 60번이 나간다.
        Map<Long, BoardDefinition> boards = loadBoardDefinitionPort.findAll().stream()
                .collect(Collectors.toMap(BoardDefinition::getId, Function.identity()));
        List<Long> postIds = comments.content().stream().map(BoardComment::getPostId).distinct().toList();
        Map<Long, String> titles = loadBoardPostPort.findTitlesByIds(postIds);
        List<Long> commentIds = comments.content().stream().map(BoardComment::getId).toList();
        Map<Long, Integer> reportCounts = loadCommentReportPort.countByCommentIds(commentIds);

        List<ModeratedComment> rows = comments.content().stream()
                .map(comment -> {
                    BoardDefinition board = boards.get(comment.getBoardId());
                    return new ModeratedComment(
                            comment,
                            board == null ? null : board.getBoardKey(),
                            board == null ? null : board.getName(),
                            titles.get(comment.getPostId()),
                            reportCounts.getOrDefault(comment.getId(), 0));
                })
                .toList();
        return BoardPage.of(rows, page, size, comments.totalElements());
    }

    @Override
    public List<CommentReport> reportsOf(Long commentId) {
        return loadCommentReportPort.findByCommentId(commentId);
    }

    @Override
    public BoardPage<CommentReport> queue(CommentReportStatus status, int page, int size) {
        return loadCommentReportPort.search(status, page, size);
    }

    @Override
    @Transactional
    public BoardComment hide(Long commentId, BoardActor actor) {
        BoardComment comment = commentOrThrow(commentId);
        comment.hide(actor, boardOf(comment), now());
        return saveBoardCommentPort.save(comment);
    }

    @Override
    @Transactional
    public BoardComment unhide(Long commentId, BoardActor actor) {
        BoardComment comment = commentOrThrow(commentId);
        comment.unhide(actor, boardOf(comment), now());
        return saveBoardCommentPort.save(comment);
    }

    @Override
    @Transactional
    public CommentReport resolve(Long reportId, CommentReportStatus decision, BoardActor actor, String handledBy) {
        CommentReport report = loadCommentReportPort.findById(reportId)
                .orElseThrow(() -> CommentReportNotFoundException.byId(reportId));

        if (decision == CommentReportStatus.HIDDEN) {
            BoardComment comment = commentOrThrow(report.getCommentId());
            // 같은 댓글에 신고가 여러 건 붙는다. 앞 건에서 이미 내려갔다면 두 번째 판정은 조치할
            // 것이 없을 뿐, 판정 자체는 기록돼야 한다 — 여기서 예외를 던지면 큐에 영영 안 닫히는
            // 건이 남는다.
            if (comment.getStatus() == BoardCommentStatus.PUBLISHED) {
                comment.hide(actor, boardOf(comment), now());
                saveBoardCommentPort.save(comment);
            }
        }

        report.resolve(decision, handledBy, now());
        return saveCommentReportPort.save(report);
    }

    private BoardDefinition readableBoard(String boardKey, BoardActor actor) {
        String normalized = boardKey == null ? null : boardKey.trim().toLowerCase(Locale.ROOT);
        BoardDefinition definition = loadBoardDefinitionPort.findByKey(normalized)
                .orElseThrow(() -> BoardNotFoundException.byKey(boardKey));
        if (!definition.isActive() || !definition.canRead(actor.role())) {
            throw BoardNotFoundException.byKey(boardKey);
        }
        return definition;
    }

    /** 다른 게시판의 댓글 식별자를 이 경로로 넣지 못하게 대조한다. */
    private BoardComment commentOfBoard(BoardDefinition definition, Long commentId) {
        BoardComment comment = commentOrThrow(commentId);
        if (!definition.getId().equals(comment.getBoardId())) {
            throw BoardCommentNotFoundException.byId(commentId);
        }
        return comment;
    }

    private BoardComment commentOrThrow(Long commentId) {
        return loadBoardCommentPort.findById(commentId)
                .orElseThrow(() -> BoardCommentNotFoundException.byId(commentId));
    }

    /** 댓글이 든 board_id 로 정책을 찾는다. 게시판이 사라졌으면 조치도 막힌다(권한 판정 불가). */
    private BoardDefinition boardOf(BoardComment comment) {
        return loadBoardDefinitionPort.findById(comment.getBoardId())
                .orElseThrow(() -> BoardNotFoundException.byKey(String.valueOf(comment.getBoardId())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
