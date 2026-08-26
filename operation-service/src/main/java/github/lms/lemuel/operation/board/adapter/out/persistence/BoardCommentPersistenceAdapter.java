package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.application.port.out.CommentSearchCriteria;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardCommentPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardCommentPort;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    public BoardPage<BoardComment> search(CommentSearchCriteria criteria, int page, int size) {
        // 최신순. id 를 마지막 키로 두어 같은 초에 달린 댓글의 순서가 페이지마다 흔들리지 않게 한다.
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Page<BoardCommentJpaEntity> result =
                repository.findAll(toSpecification(criteria), PageRequest.of(page, size, sort));

        List<BoardComment> content = result.getContent().stream()
                .map(BoardCommentJpaEntity::toDomain)
                .toList();
        return BoardPage.of(content, page, size, result.getTotalElements());
    }

    /**
     * 조건마다 <b>독립적으로</b> 붙는다.
     *
     * <p>원본(dentis)에서는 "처리 여부" 조건이 검색어 조건 안에 중첩돼 있어, 검색어 없이 미처리만
     * 보려 하면 조건이 통째로 빠졌다 — 화면은 필터가 걸린 것처럼 보이는데 결과는 전체였다.
     * 여기서는 각 필드가 서로를 가리지 않는다.
     */
    private static Specification<BoardCommentJpaEntity> toSpecification(CommentSearchCriteria criteria) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.boardId() != null) {
                predicates.add(builder.equal(root.get("boardId"), criteria.boardId()));
            }
            if (criteria.status() != null) {
                predicates.add(builder.equal(root.get("status"), criteria.status()));
            }
            if (criteria.authorId() != null) {
                predicates.add(builder.equal(root.get("authorId"), criteria.authorId()));
            }
            if (criteria.keyword() != null) {
                String pattern = "%" + criteria.keyword().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("content")), pattern),
                        builder.like(builder.lower(root.get("authorName")), pattern)));
            }
            if (criteria.reportedOnly()) {
                // 조인이 아니라 EXISTS 다 — 신고가 3건 붙은 댓글이 목록에 3줄로 늘어나면 안 된다.
                Subquery<Long> reported = query.subquery(Long.class);
                var report = reported.from(CommentReportJpaEntity.class);
                reported.select(report.get("commentId"))
                        .where(builder.equal(report.get("commentId"), root.get("id")));
                predicates.add(builder.exists(reported));
            }

            return predicates.isEmpty()
                    ? builder.conjunction()
                    : builder.and(predicates.toArray(new Predicate[0]));
        };
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
