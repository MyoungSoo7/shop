package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardAttachmentResponse;
import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardPageResponse;
import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardPostRequest;
import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardPostResponse;
import github.lms.lemuel.operation.board.application.port.in.BoardAttachmentUseCase;
import github.lms.lemuel.operation.board.application.port.in.BoardCommentUseCase;
import github.lms.lemuel.operation.board.application.port.in.ManagePostUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryPostUseCase;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAttachment;
import github.lms.lemuel.operation.board.domain.BoardPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 게시글 API — 게시판 하나 안에서만 의미가 있으므로 경로가 게시판 키에 종속된다.
 *
 * <p>모든 경로가 {@code boardKey} 를 지나는 것은 우연이 아니다. 글 식별자만으로 접근하게 두면
 * 공개 게시판의 경로로 비공개 게시판의 글을 읽는 경로가 생긴다 — 서비스가 게시판↔글 소속을
 * 대조할 수 있도록 키를 항상 함께 받는다.
 */
@Tag(name = "Board Post", description = "게시글 조회·작성")
@RestController
@RequestMapping("/api/boards/{boardKey}/posts")
@RequiredArgsConstructor
public class BoardPostController {

    private final QueryPostUseCase queryPostUseCase;
    private final ManagePostUseCase managePostUseCase;
    private final QueryBoardUseCase queryBoardUseCase;
    private final BoardAttachmentUseCase boardAttachmentUseCase;
    private final BoardCommentUseCase boardCommentUseCase;

    @Operation(summary = "게시글 목록", description = "고정 글이 먼저, 그다음 최신순. 본문은 싣지 않는다.")
    @GetMapping
    public ResponseEntity<BoardPageResponse<BoardPostResponse>> list(
            @PathVariable String boardKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {

        BoardActor actor = CurrentActor.resolve();
        boolean canManage = canManage(boardKey, actor);
        var result = queryPostUseCase.list(boardKey, actor,
                new QueryPostUseCase.PostListQuery(page, size, category, keyword));

        // 대표 이미지는 페이지 전체를 한 번에 가져온다 — 글마다 부르면 한 화면에 20번의 왕복이 된다.
        List<Long> postIds = result.content().stream().map(BoardPost::getId).toList();
        Map<Long, BoardAttachment> thumbnails =
                boardAttachmentUseCase.firstImageByPost(boardKey, postIds, actor);
        // 댓글 수도 같은 이유로 한 번에 — QNA 목록의 '답변 대기/완료' 배지가 이 값을 쓴다.
        Map<Long, Integer> commentCounts = boardCommentUseCase.countByPost(boardKey, postIds, actor);

        return ResponseEntity.ok(BoardPageResponse.from(result, post -> BoardPostResponse.summary(
                post, actor, canManage,
                thumbnailUrl(boardKey, thumbnails.get(post.getId())),
                commentCounts.getOrDefault(post.getId(), 0))));
    }

    @Operation(summary = "게시글 상세", description = "조회수가 증가한다. 볼 수 없는 글은 404.")
    @GetMapping("/{postId}")
    public ResponseEntity<BoardPostResponse> read(@PathVariable String boardKey, @PathVariable Long postId) {
        BoardActor actor = CurrentActor.resolve();
        BoardPost post = queryPostUseCase.read(boardKey, postId, actor);
        return ResponseEntity.ok(BoardPostResponse.detail(
                post, actor, canManage(boardKey, actor), attachmentsOf(boardKey, postId, actor)));
    }

    @Operation(summary = "게시글 작성")
    @PostMapping
    public ResponseEntity<BoardPostResponse> create(@PathVariable String boardKey,
                                                    @Valid @RequestBody BoardPostRequest request) {
        BoardActor actor = CurrentActor.resolve();
        BoardPost post = managePostUseCase.create(boardKey, actor, CurrentActor.requireAuthor(), request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BoardPostResponse.detail(post, actor, canManage(boardKey, actor), List.of()));
    }

    @Operation(summary = "게시글 수정", description = "작성자 본인 또는 게시판 운영 역할만.")
    @PutMapping("/{postId}")
    public ResponseEntity<BoardPostResponse> edit(@PathVariable String boardKey, @PathVariable Long postId,
                                                  @Valid @RequestBody BoardPostRequest request) {
        BoardActor actor = CurrentActor.resolve();
        BoardPost post = managePostUseCase.edit(boardKey, postId, actor, request.toCommand());
        return ResponseEntity.ok(BoardPostResponse.detail(post, actor, canManage(boardKey, actor), List.of()));
    }

    @Operation(summary = "게시글 삭제", description = "물리 삭제가 아니라 상태 전이다.")
    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> delete(@PathVariable String boardKey, @PathVariable Long postId) {
        managePostUseCase.delete(boardKey, postId, CurrentActor.resolve());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "상단 고정 · 해제", description = "게시판 운영 역할만.")
    @PostMapping("/{postId}/pin")
    public ResponseEntity<BoardPostResponse> pin(@PathVariable String boardKey, @PathVariable Long postId,
                                                 @RequestParam(defaultValue = "true") boolean pinned) {
        BoardActor actor = CurrentActor.resolve();
        BoardPost post = managePostUseCase.changePinned(boardKey, postId, actor, pinned);
        return ResponseEntity.ok(BoardPostResponse.detail(post, actor, canManage(boardKey, actor), List.of()));
    }

    @Operation(summary = "글 숨김", description = "게시판 운영 역할만. 작성자에게도 보이지 않는다.")
    @PostMapping("/{postId}/hide")
    public ResponseEntity<BoardPostResponse> hide(@PathVariable String boardKey, @PathVariable Long postId) {
        BoardActor actor = CurrentActor.resolve();
        BoardPost post = managePostUseCase.hide(boardKey, postId, actor);
        return ResponseEntity.ok(BoardPostResponse.detail(post, actor, canManage(boardKey, actor), List.of()));
    }

    @Operation(summary = "숨김 해제")
    @PostMapping("/{postId}/restore")
    public ResponseEntity<BoardPostResponse> restore(@PathVariable String boardKey, @PathVariable Long postId) {
        BoardActor actor = CurrentActor.resolve();
        BoardPost post = managePostUseCase.restore(boardKey, postId, actor);
        return ResponseEntity.ok(BoardPostResponse.detail(post, actor, canManage(boardKey, actor), List.of()));
    }

    /**
     * 응답의 {@code editable} 힌트를 채우기 위한 조회.
     *
     * <p>이 값은 <b>화면이 버튼을 그릴지</b>만 정한다. 실제 인가는 도메인이 매 조작마다 다시 하므로,
     * 여기서 틀려도 권한이 새지 않는다.
     */
    private boolean canManage(String boardKey, BoardActor actor) {
        return queryBoardUseCase.getByKey(boardKey).canManage(actor.role());
    }

    /**
     * 상세에 함께 실을 첨부 목록.
     *
     * <p>게시판이 첨부를 꺼도 <b>이미 붙은 것은 실어 보낸다</b> — 정책은 미래를 향하므로 새 업로드만
     * 막히고 기존 파일은 남는다. 화면이 그걸 못 받으면 데이터는 있는데 아무도 못 보는 상태가 된다.
     */
    private List<BoardAttachmentResponse> attachmentsOf(String boardKey, Long postId, BoardActor actor) {
        return boardAttachmentUseCase.listByPost(boardKey, postId, actor).stream()
                .map(attachment -> BoardAttachmentResponse.from(attachment, boardKey))
                .toList();
    }

    /**
     * 목록 썸네일 주소 — <b>축소본 경로</b>를 가리킨다.
     *
     * <p>원본(`/download`)을 가리키면 갤러리 한 페이지가 원본 20장을 내려받는다. 축소본이 없는
     * 형식(WEBP 등)은 그 엔드포인트가 알아서 원본으로 떨어뜨리므로 화면은 분기하지 않아도 된다.
     */
    private static String thumbnailUrl(String boardKey, BoardAttachment attachment) {
        return attachment == null
                ? null
                : "/api/boards/" + boardKey + "/attachments/" + attachment.getId() + "/thumbnail";
    }
}
