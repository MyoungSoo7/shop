package github.lms.lemuel.operation.board.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataBoardDefinitionRepository extends JpaRepository<BoardDefinitionJpaEntity, Long> {

    Optional<BoardDefinitionJpaEntity> findByBoardKey(String boardKey);

    boolean existsByBoardKey(String boardKey);

    List<BoardDefinitionJpaEntity> findAllByActiveOrderByNameAsc(boolean active);

    List<BoardDefinitionJpaEntity> findAllByOrderByNameAsc();
}
