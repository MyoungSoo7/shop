package github.lms.lemuel.operation.education.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LecturerRepository extends JpaRepository<LecturerJpaEntity, UUID> {

    /**
     * 지운 강사는 <b>어떤 조합에서도</b> 나오지 않는다 — 조건이 아니라 상수다. 파생 메서드로 풀면
     * {@code findByDeletedFalseAndActiveTrueAndNameContaining…} 처럼 조합마다 메서드가 생기고
     * 그중 하나에서 {@code deleted} 를 빠뜨리면 삭제한 사람이 조용히 목록에 돌아온다.
     *
     * <p>키워드는 이름과 소속 둘 다에 건다 — 운영자는 "김OO" 로도 "OO치과" 로도 찾는다.
     */
    @Query("""
            SELECT l FROM LecturerJpaEntity l
            WHERE l.deleted = false
              AND (:activeOnly = false OR l.active = true)
              AND (:keyword = '' OR LOWER(l.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(COALESCE(l.officeName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<LecturerJpaEntity> search(@Param("keyword") String keyword,
                                   @Param("activeOnly") boolean activeOnly,
                                   Pageable pageable);
}
