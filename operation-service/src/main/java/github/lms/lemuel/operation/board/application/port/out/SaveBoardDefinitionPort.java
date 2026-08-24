package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.BoardDefinition;

public interface SaveBoardDefinitionPort {

    BoardDefinition save(BoardDefinition definition);

    void delete(Long id);
}
