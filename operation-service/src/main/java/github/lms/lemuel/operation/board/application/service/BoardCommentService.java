package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.in.BoardCommentUseCase;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardCommentPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardCommentPort;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.exception.BoardCommentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardPostNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 댓글 응용 서비스.
 *
 * <p>댓글은 <b>글이 보여야</b> 존재할 수 있다. 그래서 모든 경로가 게시판 → 글 가시성을 먼저
 * 태우고, 통과하지 못하면 404 다 — 비밀글의 댓글 수만 새는 경로를 만들지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardCommentService implements BoardCommentUseCase {

    private final LoadBoardDefinitionPort loadBoardDefinitionPort;
    private final LoadBoardPostPort loadBoardPostPort;
    private final LoadBoardCommentPort loadBoardCommentPort;
    private final SaveBoardCommentPort saveBoardCommentPort;
    private final Clock clock;

    @Override
    public List<BoardComment> listByPost(String boardKey, Long postId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = visiblePost(definition, postId, actor);
        return loadBoardCommentPort.findByPostId(post.getId());
    }

    @Override
    public Map<Long, Integer> countByPost(String boardKey, List<Long> postIds, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        // 댓글이 꺼진 게시판은 셀 것이 없다 — 질의를 아예 내보내지 않는다.
        if (postIds == null || postIds.isEmpty() || !definition.getContentPolicy().isCommentsEnabled()) {
            return Map.of();
        }
        // 글 목록은 이미 가시성으로 걸러진 결과라 여기서 글별 판정을 다시 하지 않는다.
        return loadBoardCommentPort.countPublishedByPostIds(postIds);
    }

    @Override
    @Transactional
    public BoardComment create(String boardKey, Long postId, BoardActor actor, BoardAuthor author,
                               String content, Long parentId) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = visiblePost(definition, postId, actor);
        BoardComment parent = parentId == null ? null : parentOf(post, parentId);

        BoardComment comment = BoardComment.create(definition, post, actor, author, content, parent, now());
        return saveBoardCommentPort.save(comment);
    }

    @Override
    @Transactional
    public void delete(String boardKey, Long commentId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardComment comment = loadBoardCommentPort.findById(commentId)
                .orElseThrow(() -> BoardCommentNotFoundException.byId(commentId));
        // 다른 게시판의 댓글 식별자를 이 경로로 넣어 지우지 못하게 대조한다.
        if (!definition.getId().equals(comment.getBoardId())) {
            throw BoardCommentNotFoundException.byId(commentId);
        }
        comment.softDelete(actor, definition, now());
        saveBoardCommentPort.save(comment);
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

    private BoardPost visiblePost(BoardDefinition definition, Long postId, BoardActor actor) {
        BoardPost post = loadBoardPostPort.findById(postId)
                .orElseThrow(() -> BoardPostNotFoundException.byId(postId));
        if (!definition.getId().equals(post.getBoardId()) || !post.isVisibleTo(actor, definition)) {
            throw BoardPostNotFoundException.byId(postId);
        }
        return post;
    }

    private BoardComment parentOf(BoardPost post, Long parentId) {
        BoardComment parent = loadBoardCommentPort.findById(parentId)
                .orElseThrow(() -> BoardCommentNotFoundException.byId(parentId));
        if (!post.getId().equals(parent.getPostId())) {
            throw BoardCommentNotFoundException.byId(parentId);
        }
        return parent;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
