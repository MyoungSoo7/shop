package github.lms.lemuel.operation.board.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 목록 조회는 {@link JpaSpecificationExecutor} 로 동적 조건을 만든다.
 *
 * <p>{@code :param IS NULL OR col = :param} 형태의 JPQL 을 쓰지 않는 이유: PostgreSQL 에서
 * 타입 추론이 실패해 {@code bytea} 비교 오류가 난 전력이 있다(저장소 실측 함정). Criteria 는
 * 조건을 <b>붙이거나 안 붙이거나</b> 이므로 null 파라미터가 애초에 바인딩되지 않는다.
 */
public interface SpringDataBoardPostRepository
        extends JpaRepository<BoardPostJpaEntity, Long>, JpaSpecificationExecutor<BoardPostJpaEntity> {
}
