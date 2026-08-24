package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardDefinitionPort;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.DuplicateBoardKeyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 게시판 정의 응용 서비스.
 *
 * <p>이 서비스가 하는 일은 세 가지뿐이다: ① 커맨드 스펙을 도메인 VO 로 조립하고 ② 애그리거트를
 * 불러와 도메인 메서드를 호출하고 ③ 저장한다. <b>정합 판단은 하나도 하지 않는다</b> — 스킨과
 * 정책의 정합, 키 형식, 길이 제한은 전부 {@link BoardDefinition} 이 조립 시점에 강제한다.
 * 여기서 if 로 다시 검사하면 규칙이 두 곳에 생기고 반드시 어긋난다.
 *
 * <p>예외는 키 중복(존재 조회가 필요해 도메인이 알 수 없다)과 삭제 가드(애그리거트 하나로는
 * 판단되지만 저장소 삭제라는 응용 행위에 붙는다) 둘이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardDefinitionService implements ManageBoardUseCase, QueryBoardUseCase {

    private final LoadBoardDefinitionPort loadBoardDefinitionPort;
    private final SaveBoardDefinitionPort saveBoardDefinitionPort;
    private final Clock clock;

    @Override
    @Transactional
    public BoardDefinition create(CreateBoardCommand command) {
        BoardDefinition definition = BoardDefinition.create(
                command.boardKey(),
                command.name(),
                command.description(),
                command.skin(),
                toContentPolicy(command.content()),
                toAttachmentPolicy(command.attachment()),
                toAccessPolicy(command.access()),
                now());

        // 정규화된 키로 조회해야 'Notice' 와 'notice' 가 다른 게시판으로 새는 것을 막는다.
        if (loadBoardDefinitionPort.existsByKey(definition.getBoardKey())) {
            throw new DuplicateBoardKeyException(definition.getBoardKey());
        }
        return saveBoardDefinitionPort.save(definition);
    }

    @Override
    @Transactional
    public BoardDefinition update(Long id, UpdateBoardCommand command) {
        BoardDefinition definition = getById(id);
        definition.update(
                command.name(),
                command.description(),
                command.skin(),
                toContentPolicy(command.content()),
                toAttachmentPolicy(command.attachment()),
                toAccessPolicy(command.access()),
                now());
        return saveBoardDefinitionPort.save(definition);
    }

    @Override
    @Transactional
    public BoardDefinition deactivate(Long id) {
        BoardDefinition definition = getById(id);
        definition.deactivate(now());
        return saveBoardDefinitionPort.save(definition);
    }

    @Override
    @Transactional
    public BoardDefinition activate(Long id) {
        BoardDefinition definition = getById(id);
        definition.activate(now());
        return saveBoardDefinitionPort.save(definition);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        BoardDefinition definition = getById(id);
        if (definition.isActive()) {
            throw new BoardInvariantViolationException(
                    "운영 중인 게시판은 삭제할 수 없습니다. 먼저 비활성화하세요: " + definition.getBoardKey());
        }
        saveBoardDefinitionPort.delete(id);
    }

    @Override
    public List<BoardDefinition> findAll() {
        return loadBoardDefinitionPort.findAll();
    }

    @Override
    public List<BoardDefinition> findActive() {
        return loadBoardDefinitionPort.findByActive(true);
    }

    @Override
    public BoardDefinition getById(Long id) {
        return loadBoardDefinitionPort.findById(id)
                .orElseThrow(() -> BoardNotFoundException.byId(id));
    }

    @Override
    public BoardDefinition getByKey(String boardKey) {
        String normalized = boardKey == null ? null : boardKey.trim().toLowerCase(Locale.ROOT);
        return loadBoardDefinitionPort.findByKey(normalized)
                .orElseThrow(() -> BoardNotFoundException.byKey(boardKey));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static BoardContentPolicy toContentPolicy(ContentSpec spec) {
        if (spec == null) {
            throw new BoardInvariantViolationException("본문 정책은 필수입니다.");
        }
        return BoardContentPolicy.of(spec.contentFormat(), spec.commentsEnabled(),
                spec.secretEnabled(), spec.categoryGroupCode());
    }

    private static BoardAttachmentPolicy toAttachmentPolicy(AttachmentSpec spec) {
        if (spec == null) {
            throw new BoardInvariantViolationException("첨부 정책은 필수입니다.");
        }
        // 꺼진 정책은 입력값을 버리고 정규형으로 접는다 — "불가인데 최대 5개"가 저장되지 않게.
        return spec.enabled()
                ? BoardAttachmentPolicy.enabled(spec.maxCount(), spec.maxSizeKb(), spec.allowedExtensions())
                : BoardAttachmentPolicy.disabled();
    }

    private static BoardAccessPolicy toAccessPolicy(AccessSpec spec) {
        if (spec == null) {
            throw new BoardInvariantViolationException("접근 정책은 필수입니다.");
        }
        return BoardAccessPolicy.of(spec.readRoles(), spec.writeRoles(), spec.commentRoles(), spec.manageRoles());
    }
}
