package github.lms.lemuel.operation.board.application.port.in;

import github.lms.lemuel.operation.board.domain.BoardDefinition;

import java.util.List;

/**
 * 게시판 정의 조회 유스케이스.
 *
 * <p>관리 화면은 닫힌 게시판까지 봐야 하고(다시 열려면 보여야 한다), 이용 화면은 열린 것만
 * 봐야 한다. 두 요구가 하나의 메서드에 boolean 파라미터로 섞이면 호출부가 실수한다 — 갈라 둔다.
 */
public interface QueryBoardUseCase {

    /** 관리용 — 비활성 포함 전체. */
    List<BoardDefinition> findAll();

    /** 이용용 — 활성 게시판만. */
    List<BoardDefinition> findActive();

    BoardDefinition getById(Long id);

    BoardDefinition getByKey(String boardKey);
}
