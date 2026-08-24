package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardCommentRequest;
import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardCommentResponse;
import github.lms.lemuel.operation.board.application.port.in.BoardCommentUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardComment;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Board Comment", description = "댓글 조회·작성·삭제")
@RestController
@RequestMapping("/api/boards/{boardKey}")
@RequiredArgsConstructor
public class BoardCommentController {

    private final BoardCommentUseCase boardCommentUseCase;
    private final QueryBoardUseCase queryBoardUseCase;

    @Operation(summary = "댓글 목록", description = "삭제된 댓글도 자리표시로 남는다(대화의 앞말 보존).")
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<List<BoardCommentResponse>> list(@PathVariable String boardKey,
                                                           @PathVariable Long postId) {
        BoardActor actor = CurrentActor.resolve();
        boolean canManage = canManage(boardKey, actor);
        List<BoardCommentResponse> comments = boardCommentUseCase.listByPost(boardKey, postId, actor).stream()
                .map(comment -> BoardCommentResponse.from(comment, actor, canManage))
                .toList();
        return ResponseEntity.ok(comments);
    }

    @Operation(summary = "댓글 작성", description = "답글은 1단까지. 댓글이 꺼진 게시판은 403.")
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<BoardCommentResponse> create(@PathVariable String boardKey, @PathVariable Long postId,
                                                       @Valid @RequestBody BoardCommentRequest request) {
        BoardActor actor = CurrentActor.resolve();
        BoardComment comment = boardCommentUseCase.create(boardKey, postId, actor,
                CurrentActor.requireAuthor(), request.content(), request.parentId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BoardCommentResponse.from(comment, actor, canManage(boardKey, actor)));
    }

    @Operation(summary = "댓글 삭제", description = "작성자 본인 또는 게시판 운영 역할만.")
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable String boardKey, @PathVariable Long commentId) {
        boardCommentUseCase.delete(boardKey, commentId, CurrentActor.resolve());
        return ResponseEntity.noContent().build();
    }

    private boolean canManage(String boardKey, BoardActor actor) {
        return queryBoardUseCase.getByKey(boardKey).canManage(actor.role());
    }
}
