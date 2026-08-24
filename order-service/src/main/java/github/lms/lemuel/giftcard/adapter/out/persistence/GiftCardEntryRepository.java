package github.lms.lemuel.giftcard.adapter.out.persistence;

import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GiftCardEntryRepository extends JpaRepository<GiftCardEntryJpaEntity, Long> {

    boolean existsByGiftCardIdAndEntryTypeAndReferenceTypeAndReferenceId(
            Long giftCardId, GiftCardEntryType entryType, String referenceType, String referenceId);

    boolean existsByGiftCardIdAndEntryTypeAndReferenceTypeAndReferenceIdAndSequence(
            Long giftCardId, GiftCardEntryType entryType, String referenceType,
            String referenceId, int sequence);

    /** 카드를 가로질러 같은 참조의 엔트리를 모은다 — 한 결제가 여러 장을 걸치기 때문이다. */
    List<GiftCardEntryJpaEntity> findByEntryTypeAndReferenceTypeAndReferenceIdOrderByIdAsc(
            GiftCardEntryType entryType, String referenceType, String referenceId);

    /** 다음 sequence 산출용 — 행이 없으면 -1 을 돌려 호출자가 0 부터 시작하게 한다. */
    @Query("""
            select coalesce(max(e.sequence), -1) from GiftCardEntryJpaEntity e
            where e.giftCardId = :giftCardId and e.entryType = :entryType
              and e.referenceType = :referenceType and e.referenceId = :referenceId
            """)
    int maxSequence(@Param("giftCardId") Long giftCardId,
                    @Param("entryType") GiftCardEntryType entryType,
                    @Param("referenceType") String referenceType,
                    @Param("referenceId") String referenceId);
}
