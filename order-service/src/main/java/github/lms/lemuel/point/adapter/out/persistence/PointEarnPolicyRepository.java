package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointEarnScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 적립률 정책 조회.
 *
 * <p>조건에 <b>null 파라미터를 넣지 않는다</b>. {@code :param IS NULL OR ...} 패턴은 이 저장소에서
 * PostgreSQL 타입 추론(bytea) 오류로 터진 전례가 있어, 키 있는 정책과 전역 정책을 메서드로 나눈다.
 */
public interface PointEarnPolicyRepository extends JpaRepository<PointEarnPolicyJpaEntity, Long> {

    @Query("""
            select p from PointEarnPolicyJpaEntity p
            where p.scope = :scope and p.effectiveFrom <= :on
            """)
    List<PointEarnPolicyJpaEntity> findByScope(@Param("scope") PointEarnScope scope,
                                               @Param("on") LocalDate on);

    @Query("""
            select p from PointEarnPolicyJpaEntity p
            where p.scope = :scope and p.scopeKey = :scopeKey and p.effectiveFrom <= :on
            """)
    List<PointEarnPolicyJpaEntity> findByScopeAndKey(@Param("scope") PointEarnScope scope,
                                                     @Param("scopeKey") String scopeKey,
                                                     @Param("on") LocalDate on);
}
