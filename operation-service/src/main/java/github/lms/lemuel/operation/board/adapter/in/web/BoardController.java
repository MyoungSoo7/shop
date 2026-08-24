package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardDefinitionResponse;
import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 게시판 이용 API — 프론트의 단일 라우트 {@code /boards/:boardKey} 가 화면을 그리기 위해 읽는다.
 *
 * <p><b>가시성 판정은 도메인이 한다.</b> 보안 필터는 이 경로를 열어 두고(공개 게시판이 존재하므로),
 * 어떤 게시판이 응답에 담기는지는 {@link BoardDefinition#canRead(String)} 이 정한다.
 *
 * <p>읽을 수 없는 게시판은 403 이 아니라 <b>404</b> 다. 403 은 "여기 뭔가 있다"를 알려 주는
 * 응답이라, 비공개 게시판의 키를 대입해 존재 여부를 훑을 수 있게 된다.
 */
@Tag(name = "Board", description = "게시판 정의 조회(이용)")
@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
public class BoardController {

    private final QueryBoardUseCase queryBoardUseCase;

    @Operation(summary = "이용 가능한 게시판 목록", description = "활성 + 호출자가 읽을 수 있는 것만.")
    @GetMapping
    public ResponseEntity<List<BoardDefinitionResponse>> list() {
        String role = CurrentRole.resolve();
        return ResponseEntity.ok(queryBoardUseCase.findActive().stream()
                .filter(definition -> definition.canRead(role))
                .map(BoardDefinitionResponse::from)
                .toList());
    }

    @Operation(summary = "게시판 정의 조회", description = "닫혔거나 읽기 권한이 없으면 404.")
    @GetMapping("/{boardKey}")
    public ResponseEntity<BoardDefinitionResponse> get(@PathVariable String boardKey) {
        BoardDefinition definition = queryBoardUseCase.getByKey(boardKey);
        if (!definition.isActive() || !definition.canRead(CurrentRole.resolve())) {
            throw BoardNotFoundException.byKey(boardKey);
        }
        return ResponseEntity.ok(BoardDefinitionResponse.from(definition));
    }
}
