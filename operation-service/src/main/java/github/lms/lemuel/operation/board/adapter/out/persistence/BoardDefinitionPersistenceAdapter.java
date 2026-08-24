package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardDefinitionPort;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BoardDefinitionPersistenceAdapter implements LoadBoardDefinitionPort, SaveBoardDefinitionPort {

    private final SpringDataBoardDefinitionRepository repository;

    @Override
    public Optional<BoardDefinition> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(BoardDefinitionJpaEntity::toDomain);
    }

    @Override
    public Optional<BoardDefinition> findByKey(String boardKey) {
        if (boardKey == null || boardKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByBoardKey(boardKey).map(BoardDefinitionJpaEntity::toDomain);
    }

    @Override
    public boolean existsByKey(String boardKey) {
        return boardKey != null && !boardKey.isBlank() && repository.existsByBoardKey(boardKey);
    }

    @Override
    public List<BoardDefinition> findAll() {
        return repository.findAllByOrderByNameAsc().stream()
                .map(BoardDefinitionJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<BoardDefinition> findByActive(boolean active) {
        return repository.findAllByActiveOrderByNameAsc(active).stream()
                .map(BoardDefinitionJpaEntity::toDomain)
                .toList();
    }

    @Override
    public BoardDefinition save(BoardDefinition definition) {
        // 기존 행이면 같은 엔티티에 덮어써 JPA 가 UPDATE 로 처리하게 한다. from() 으로 새 인스턴스를
        // 만들어 merge 시키면 준영속 객체가 생겨 낙관적 락을 붙일 때 곧바로 문제가 된다.
        BoardDefinitionJpaEntity entity = definition.getId() == null
                ? BoardDefinitionJpaEntity.from(definition)
                : repository.findById(definition.getId())
                .map(existing -> {
                    existing.apply(definition);
                    return existing;
                })
                .orElseGet(() -> BoardDefinitionJpaEntity.from(definition));

        return repository.save(entity).toDomain();
    }

    @Override
    public void delete(Long id) {
        repository.deleteById(id);
    }
}
