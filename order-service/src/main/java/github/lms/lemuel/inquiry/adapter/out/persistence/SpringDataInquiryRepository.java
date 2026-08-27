package github.lms.lemuel.inquiry.adapter.out.persistence;

import github.lms.lemuel.inquiry.domain.InquiryType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SpringDataInquiryRepository extends JpaRepository<InquiryJpaEntity, Long> {

    List<InquiryJpaEntity> findByUserIdOrderByAskedAtDesc(Long userId, Pageable pageable);

    List<InquiryJpaEntity> findByUserIdAndTypeOrderByAskedAtDesc(Long userId, InquiryType type, Pageable pageable);

    List<InquiryJpaEntity> findByProductIdOrderByAskedAtDesc(Long productId, Pageable pageable);

    /**
     * 아직 답변이 없는 문의. 오래된 순 — 먼저 물어본 사람이 먼저다.
     *
     * <p>판정은 저장된 상태 칼럼이 아니라 <b>답변 행의 유무</b>다. 레거시는 이 값을 질문 행의
     * 칼럼으로 들고 있었고, 답변 삭제 경로가 그 칼럼을 되돌리지 않아 목록과 상세가 어긋났다.
     */
    @Query("""
            SELECT i FROM InquiryJpaEntity i
            WHERE NOT EXISTS (
                SELECT 1 FROM InquiryAnswerJpaEntity a WHERE a.inquiryId = i.id
            )
            ORDER BY i.askedAt ASC
            """)
    List<InquiryJpaEntity> findWaiting(Pageable pageable);
}
