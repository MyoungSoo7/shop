package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.application.port.out.LoadBoardCommentPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardCommentPort;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class BoardCommentPersistenceAdapter implements LoadBoardCommentPort, SaveBoardCommentPort {

    private final SpringDataBoardCommentRepository repository;

    @Override
    public Optional<BoardComment> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(BoardCommentJpaEntity::toDomain);
    }

    @Override
    public List<BoardComment> findByPostId(Long postId) {
        return repository.findAllByPostIdOrderByIdAsc(postId).stream()
                .map(BoardCommentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Map<Long, Integer> countPublishedByPostIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        return repository.countByPostIdIn(postIds, BoardCommentStatus.PUBLISHED).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).intValue()));
    }

    @Override
    public BoardComment save(BoardComment comment) {
        BoardCommentJpaEntity entity = comment.getId() == null
                ? BoardCommentJpaEntity.from(comment)
                : repository.findById(comment.getId())
                .map(existing -> {
                    existing.apply(comment);
                    return existing;
                })
                .orElseGet(() -> BoardCommentJpaEntity.from(comment));

        return repository.save(entity).toDomain();
    }
}
