package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.application.port.out.LoadCommentReportPort;
import github.lms.lemuel.operation.board.application.port.out.SaveCommentReportPort;
import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CommentReportPersistenceAdapter implements LoadCommentReportPort, SaveCommentReportPort {

    private final SpringDataCommentReportRepository repository;

    @Override
    public Optional<CommentReport> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(CommentReportJpaEntity::toDomain);
    }

    @Override
    public BoardPage<CommentReport> search(CommentReportStatus status, int page, int size) {
        // 오래된 순. 큐는 먼저 들어온 신고를 먼저 처리해야 하고, 최신순으로 두면 밀린 건이 영영 뒤로 밀린다.
        Sort sort = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        PageRequest pageable = PageRequest.of(page, size, sort);
        Page<CommentReportJpaEntity> result = status == null
                ? repository.findAll(pageable)
                : repository.findAllByStatus(status, pageable);

        List<CommentReport> content = result.getContent().stream()
                .map(CommentReportJpaEntity::toDomain)
                .toList();
        return BoardPage.of(content, page, size, result.getTotalElements());
    }

    @Override
    public List<CommentReport> findByCommentId(Long commentId) {
        if (commentId == null) {
            return List.of();
        }
        return repository.findAllByCommentIdOrderByIdAsc(commentId).stream()
                .map(CommentReportJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Map<Long, Integer> countByCommentIds(List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Map.of();
        }
        return repository.countByCommentIdIn(commentIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).intValue()));
    }

    @Override
    public boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId) {
        if (commentId == null || reporterId == null) {
            return false;
        }
        return repository.existsByCommentIdAndReporterId(commentId, reporterId);
    }

    @Override
    public CommentReport save(CommentReport report) {
        CommentReportJpaEntity entity = report.getId() == null
                ? CommentReportJpaEntity.from(report)
                : repository.findById(report.getId())
                .map(existing -> {
                    existing.apply(report);
                    return existing;
                })
                .orElseGet(() -> CommentReportJpaEntity.from(report));

        return repository.save(entity).toDomain();
    }
}
