package github.lms.lemuel.operation.board.application.port.in;

import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;

import java.util.List;

/**
 * 게시판 정의 관리 유스케이스 — 관리자가 게시판을 만들고 정책을 바꾸고 닫는 경로.
 *
 * <p>커맨드는 도메인 VO 를 그대로 받지 않고 <b>평평한 스펙 레코드</b>로 받는다. 웹 어댑터가
 * 도메인 VO 조립 규칙(첨부 꺼짐 = 0/0/빈집합 같은 정규화)을 알아야 한다면 그 규칙이 어댑터마다
 * 복제된다. 스펙 → VO 변환은 응용 서비스 한 곳에서만 일어난다.
 */
public interface ManageBoardUseCase {

    BoardDefinition create(CreateBoardCommand command);

    BoardDefinition update(Long id, UpdateBoardCommand command);

    /** 게시판을 닫는다(비활성). 링크가 살아 있는 채로 사라지지 않도록 삭제와 분리한다. */
    BoardDefinition deactivate(Long id);

    BoardDefinition activate(Long id);

    /**
     * 물리 삭제. <b>닫힌 게시판만</b> 지울 수 있다 — 운영 중인 게시판을 한 번의 호출로 지우면
     * 이미 배포된 링크와 메뉴 행이 동시에 죽고 되돌릴 수 없다.
     */
    void delete(Long id);

    record ContentSpec(
            BoardContentFormat contentFormat,
            boolean commentsEnabled,
            boolean secretEnabled,
            String categoryGroupCode) {
    }

    record AttachmentSpec(
            boolean enabled,
            int maxCount,
            int maxSizeKb,
            List<String> allowedExtensions) {
    }

    record AccessSpec(
            List<String> readRoles,
            List<String> writeRoles,
            List<String> commentRoles,
            List<String> manageRoles) {
    }

    record CreateBoardCommand(
            String boardKey,
            String name,
            String description,
            BoardSkin skin,
            ContentSpec content,
            AttachmentSpec attachment,
            AccessSpec access) {
    }

    /** 키는 없다 — 키 변경은 지원하지 않는다({@link BoardDefinition#update} javadoc 참조). */
    record UpdateBoardCommand(
            String name,
            String description,
            BoardSkin skin,
            ContentSpec content,
            AttachmentSpec attachment,
            AccessSpec access) {
    }
}
