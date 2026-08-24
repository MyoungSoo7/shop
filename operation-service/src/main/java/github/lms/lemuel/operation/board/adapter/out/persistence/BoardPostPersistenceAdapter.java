package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.operation.board.application.port.out.PostSearchCriteria;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardPostPort;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.BoardPostStatus;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BoardPostPersistenceAdapter implements LoadBoardPostPort, SaveBoardPostPort {

    private final SpringDataBoardPostRepository repository;

    @Override
    public Optional<BoardPost> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(BoardPostJpaEntity::toDomain);
    }

    @Override
    public BoardPage<BoardPost> search(PostSearchCriteria criteria, int page, int size) {
        // 고정 글이 먼저, 그다음 최신순. id 를 마지막 키로 두어 같은 초에 쓰인 글의 순서가 흔들리지 않게 한다.
        Sort sort = Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Page<BoardPostJpaEntity> result =
                repository.findAll(toSpecification(criteria), PageRequest.of(page, size, sort));

        List<BoardPost> content = result.getContent().stream()
                .map(BoardPostJpaEntity::toDomain)
                .toList();
        return BoardPage.of(content, page, size, result.getTotalElements());
    }

    /**
     * 조건을 <b>붙이거나 안 붙인다</b> — null 파라미터를 바인딩하지 않으므로 PostgreSQL 타입 추론
     * 실패({@code bytea} 비교 오류)가 구조적으로 생기지 않는다.
     */
    private static Specification<BoardPostJpaEntity> toSpecification(PostSearchCriteria criteria) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("boardId"), criteria.boardId()));

            // 삭제 글은 누구에게도 나가지 않는다. 숨김 글은 운영 역할에게만.
            if (criteria.includeHidden()) {
                predicates.add(builder.notEqual(root.get("status"), BoardPostStatus.DELETED));
            } else {
                predicates.add(builder.equal(root.get("status"), BoardPostStatus.PUBLISHED));
            }

            if (criteria.categoryCode() != null) {
                predicates.add(builder.equal(root.get("categoryCode"), criteria.categoryCode()));
            }

            if (criteria.keyword() != null) {
                String pattern = "%" + criteria.keyword().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern),
                        builder.like(builder.lower(root.get("content")), pattern)));
            }

            if (!criteria.includeAllSecret()) {
                Predicate notSecret = builder.isFalse(root.get("secret"));
                predicates.add(criteria.viewerId() == null
                        ? notSecret
                        : builder.or(notSecret, builder.equal(root.get("authorId"), criteria.viewerId())));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Override
    public BoardPost save(BoardPost post) {
        BoardPostJpaEntity entity = post.getId() == null
                ? BoardPostJpaEntity.from(post)
                : repository.findById(post.getId())
                .map(existing -> {
                    existing.apply(post);
                    return existing;
                })
                .orElseGet(() -> BoardPostJpaEntity.from(post));

        return repository.save(entity).toDomain();
    }
}
