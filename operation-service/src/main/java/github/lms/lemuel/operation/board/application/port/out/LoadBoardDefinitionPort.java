package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.BoardDefinition;

import java.util.List;
import java.util.Optional;

public interface LoadBoardDefinitionPort {

    Optional<BoardDefinition> findById(Long id);

    Optional<BoardDefinition> findByKey(String boardKey);

    boolean existsByKey(String boardKey);

    List<BoardDefinition> findAll();

    List<BoardDefinition> findByActive(boolean active);
}
