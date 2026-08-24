package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PointEntryRepository extends JpaRepository<PointEntryJpaEntity, Long> {

    List<PointEntryJpaEntity> findByAccountIdAndEntryTypeAndReferenceTypeAndReferenceIdOrderBySequenceAsc(
            Long accountId, PointEntryType entryType, String referenceType, String referenceId);

    boolean existsByAccountIdAndEntryTypeAndReferenceTypeAndReferenceId(
            Long accountId, PointEntryType entryType, String referenceType, String referenceId);

    boolean existsByAccountIdAndEntryTypeAndReferenceTypeAndReferenceIdAndSequence(
            Long accountId, PointEntryType entryType, String referenceType, String referenceId, int sequence);

    /** 다음 sequence 산출용 — 행이 없으면 -1 을 돌려 호출자가 0 부터 시작하게 한다. */
    @Query("""
            select coalesce(max(e.sequence), -1) from PointEntryJpaEntity e
            where e.accountId = :accountId and e.entryType = :entryType
              and e.referenceType = :referenceType and e.referenceId = :referenceId
            """)
    int maxSequence(@Param("accountId") Long accountId,
                    @Param("entryType") PointEntryType entryType,
                    @Param("referenceType") String referenceType,
                    @Param("referenceId") String referenceId);

    /** 그 참조의 엔트리가 속한 계정. 환불 복원이 "낸 사람"을 원장에서 찾을 때 쓴다. */
    @Query("""
            select distinct e.accountId from PointEntryJpaEntity e
            where e.entryType = :entryType
              and e.referenceType = :referenceType and e.referenceId = :referenceId
            """)
    List<Long> findAccountIds(@Param("entryType") PointEntryType entryType,
                              @Param("referenceType") String referenceType,
                              @Param("referenceId") String referenceId);
}
