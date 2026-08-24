package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardCreateRequest;
import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardDefinitionResponse;
import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardUpdateRequest;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 게시판 관리 콘솔 API — 관리자가 게시판을 만들고 정책을 바꾸고 닫는다.
 *
 * <p>게시판을 만드는 것과 <b>메뉴에 올리는 것은 별개 조작</b>이다. 여기서 메뉴 행을 만들지
 * 않는 이유는 두 가지다: ① 메뉴는 order-service 소유라 여기서 쓰면 DB 경계를 넘고,
 * ② 게시판 생성이 곧 전사 네비게이션 변경이 되면 테스트로 만든 게시판·오타 난 이름이 즉시
 * 모두에게 노출된다. 관리 화면이 생성 직후 기존 {@code POST /admin/menus} 를 한 번 더 호출한다
 * (docs/plan/board-service.md §6).
 */
@Tag(name = "Board Admin", description = "게시판 정의 관리(ADMIN)")
@RestController
@RequestMapping("/admin/boards")
@RequiredArgsConstructor
public class AdminBoardController {

    private final ManageBoardUseCase manageBoardUseCase;
    private final QueryBoardUseCase queryBoardUseCase;

    @Operation(summary = "게시판 목록 조회", description = "비활성 게시판을 포함한 전체 목록.")
    @GetMapping
    public ResponseEntity<List<BoardDefinitionResponse>> list() {
        return ResponseEntity.ok(queryBoardUseCase.findAll().stream()
                .map(BoardDefinitionResponse::from)
                .toList());
    }

    @Operation(summary = "게시판 단건 조회")
    @GetMapping("/{id}")
    public ResponseEntity<BoardDefinitionResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(BoardDefinitionResponse.from(queryBoardUseCase.getById(id)));
    }

    @Operation(summary = "게시판 생성", description = "키 중복이면 409. 스킨과 정책이 어긋나면 400.")
    @PostMapping
    public ResponseEntity<BoardDefinitionResponse> create(@Valid @RequestBody BoardCreateRequest request) {
        BoardDefinitionResponse response = BoardDefinitionResponse.from(
                manageBoardUseCase.create(request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "게시판 정책 수정", description = "게시판 키는 바꿀 수 없다.")
    @PutMapping("/{id}")
    public ResponseEntity<BoardDefinitionResponse> update(@PathVariable Long id,
                                                          @Valid @RequestBody BoardUpdateRequest request) {
        return ResponseEntity.ok(BoardDefinitionResponse.from(
                manageBoardUseCase.update(id, request.toCommand())));
    }

    @Operation(summary = "게시판 닫기", description = "삭제가 아니라 비활성. 링크는 살아 있고 목록에서만 빠진다.")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<BoardDefinitionResponse> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(BoardDefinitionResponse.from(manageBoardUseCase.deactivate(id)));
    }

    @Operation(summary = "게시판 다시 열기")
    @PostMapping("/{id}/activate")
    public ResponseEntity<BoardDefinitionResponse> activate(@PathVariable Long id) {
        return ResponseEntity.ok(BoardDefinitionResponse.from(manageBoardUseCase.activate(id)));
    }

    @Operation(summary = "게시판 삭제", description = "닫힌 게시판만 삭제할 수 있다. 운영 중이면 400.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        manageBoardUseCase.delete(id);
        return ResponseEntity.noContent().build();
    }
}
