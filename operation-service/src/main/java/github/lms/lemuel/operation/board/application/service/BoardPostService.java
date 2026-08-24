package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.application.port.in.ManagePostUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryPostUseCase;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.operation.board.application.port.out.PostSearchCriteria;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardPostPort;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardPostNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * 게시글 응용 서비스.
 *
 * <p>하는 일은 세 가지다: ① 게시판 정의를 불러와 <b>읽을 수 있는지 판정</b>하고 ② 애그리거트에
 * 도메인 메서드를 호출하고 ③ 저장한다. 소유권·역할 판정은 하나도 하지 않는다 —
 * 전부 {@link BoardPost} 가 {@link BoardActor} 를 받아 스스로 한다.
 *
 * <p>가시성만은 예외적으로 여기서 <b>질의 조건으로 번역</b>한다(§목록). 판정 기준은 도메인과
 * 같지만, 페이지네이션이 걸린 목록에서 자바로 걸러 내면 총건수와 페이지 크기가 어긋나기 때문이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardPostService implements ManagePostUseCase, QueryPostUseCase {

    private final LoadBoardDefinitionPort loadBoardDefinitionPort;
    private final LoadBoardPostPort loadBoardPostPort;
    private final SaveBoardPostPort saveBoardPostPort;
    private final BoardContentSanitizer contentSanitizer;
    private final Clock clock;

    @Override
    @Transactional
    public BoardPost create(String boardKey, BoardActor actor, BoardAuthor author, PostContentCommand command) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = BoardPost.create(definition, actor, author,
                command.title(), contentSanitizer.sanitize(definition, command.content()),
                command.categoryCode(), command.secret(), now());
        return saveBoardPostPort.save(post);
    }

    @Override
    @Transactional
    public BoardPost edit(String boardKey, Long postId, BoardActor actor, PostContentCommand command) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = postOf(definition, postId);
        // 수정도 작성과 같은 정화를 거친다 — 한쪽만 정화하면 "수정으로 심는" 우회 경로가 남는다.
        post.edit(actor, definition, command.title(), contentSanitizer.sanitize(definition, command.content()),
                command.categoryCode(), command.secret(), now());
        return saveBoardPostPort.save(post);
    }

    @Override
    @Transactional
    public void delete(String boardKey, Long postId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = postOf(definition, postId);
        post.softDelete(actor, definition, now());
        saveBoardPostPort.save(post);
    }

    @Override
    @Transactional
    public BoardPost changePinned(String boardKey, Long postId, BoardActor actor, boolean pinned) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = postOf(definition, postId);
        post.changePinned(actor, definition, pinned, now());
        return saveBoardPostPort.save(post);
    }

    @Override
    @Transactional
    public BoardPost hide(String boardKey, Long postId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = postOf(definition, postId);
        post.hide(actor, definition, now());
        return saveBoardPostPort.save(post);
    }

    @Override
    @Transactional
    public BoardPost restore(String boardKey, Long postId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = postOf(definition, postId);
        post.restore(actor, definition, now());
        return saveBoardPostPort.save(post);
    }

    @Override
    public BoardPage<BoardPost> list(String boardKey, BoardActor actor, PostListQuery query) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        boolean canManage = definition.canManage(actor.role());
        PostSearchCriteria criteria = new PostSearchCriteria(
                definition.getId(),
                normalizeCategory(query.categoryCode()),
                blankToNull(query.keyword()),
                canManage,
                canManage,
                actor.userId());
        return loadBoardPostPort.search(criteria, query.page(), query.size());
    }

    @Override
    @Transactional
    public BoardPost read(String boardKey, Long postId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = postOf(definition, postId);
        if (!post.isVisibleTo(actor, definition)) {
            // 볼 수 없다 = 없다. 403 으로 가르면 식별자를 훑어 비밀글의 존재를 알 수 있다.
            throw BoardPostNotFoundException.byId(postId);
        }
        post.increaseView();
        return saveBoardPostPort.save(post);
    }

    /**
     * 게시판을 불러오되, 호출자가 읽을 수 없거나 닫힌 게시판이면 <b>없는 것으로</b> 답한다.
     *
     * <p>쓰기 경로에서도 같은 판정을 먼저 태운다 — 읽을 수도 없는 게시판에 글을 쓰다 권한 오류를
     * 받으면 그 자체가 게시판의 존재를 알려 주는 신호가 된다.
     */
    private BoardDefinition readableBoard(String boardKey, BoardActor actor) {
        String normalized = boardKey == null ? null : boardKey.trim().toLowerCase(Locale.ROOT);
        BoardDefinition definition = loadBoardDefinitionPort.findByKey(normalized)
                .orElseThrow(() -> BoardNotFoundException.byKey(boardKey));
        if (!definition.isActive() || !definition.canRead(actor.role())) {
            throw BoardNotFoundException.byKey(boardKey);
        }
        return definition;
    }

    /**
     * 글을 불러오되 <b>그 게시판의 글인지</b> 대조한다. 대조하지 않으면 공개 게시판 경로로
     * 비공개 게시판의 글 식별자를 넣어 읽을 수 있다.
     */
    private BoardPost postOf(BoardDefinition definition, Long postId) {
        BoardPost post = loadBoardPostPort.findById(postId)
                .orElseThrow(() -> BoardPostNotFoundException.byId(postId));
        if (!definition.getId().equals(post.getBoardId())) {
            throw BoardPostNotFoundException.byId(postId);
        }
        return post;
    }

    private static String normalizeCategory(String categoryCode) {
        String trimmed = blankToNull(categoryCode);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
