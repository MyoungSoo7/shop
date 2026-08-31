package github.lms.lemuel.seller.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 신청서 조회.
 *
 * <p>상태 필터가 있는 쿼리와 없는 쿼리를 <b>따로</b> 뒀다. 하나로 합치려면
 * {@code (:status IS NULL OR status = :status)} 가 필요한데, PostgreSQL 은 양쪽이 모두 미지
 * 타입인 비교의 타입을 추론하지 못해 거절한다. {@code CAST} 로 감쌀 수도 있지만, 여기서는
 * 분기가 어댑터에 한 줄 생길 뿐이라 굳이 SQL 을 어렵게 만들지 않는다.
 *
 * <p>정렬에 {@code submission_id} tiebreaker 를 붙인 것은 페이징 때문이다. 같은 초에 만들어진
 * 신청서가 실제로 생기고(대량 등록), 정렬이 불안정하면 2페이지에 1페이지 행이 다시 나오거나
 * 어떤 신청서는 목록에서 영영 사라진다.
 */
interface ProductSubmissionJpaRepository extends JpaRepository<ProductSubmissionJpaEntity, Long> {

    /**
     * 셀러 범위 조회. {@code sellerId} 를 조건에 <b>반드시</b> 함께 건다 — 신청서 번호만으로
     * 찾으면 남의 신청서 번호를 넣는 것만으로 남의 상품 정보가 열린다(IDOR).
     */
    @Query(value = """
            SELECT * FROM seller.product_submissions
             WHERE submission_id = :submissionId
               AND seller_id = :sellerId
            """, nativeQuery = true)
    Optional<ProductSubmissionJpaEntity> findOwned(@Param("submissionId") long submissionId,
                                                   @Param("sellerId") long sellerId);

    long countBySellerId(long sellerId);

    long countBySellerIdAndStatus(long sellerId, String status);

    @Query(value = """
            SELECT * FROM seller.product_submissions
             WHERE seller_id = :sellerId
             ORDER BY created_at DESC, submission_id DESC
             LIMIT :maxRows OFFSET :skip
            """, nativeQuery = true)
    List<ProductSubmissionJpaEntity> findBySeller(@Param("sellerId") long sellerId,
                                                  @Param("maxRows") int maxRows,
                                                  @Param("skip") long skip);

    @Query(value = """
            SELECT * FROM seller.product_submissions
             WHERE seller_id = :sellerId
               AND status = :status
             ORDER BY created_at DESC, submission_id DESC
             LIMIT :maxRows OFFSET :skip
            """, nativeQuery = true)
    List<ProductSubmissionJpaEntity> findBySellerAndStatus(@Param("sellerId") long sellerId,
                                                           @Param("status") String status,
                                                           @Param("maxRows") int maxRows,
                                                           @Param("skip") long skip);

    /**
     * 심사 대기열은 <b>제출 순서대로</b> 본다. 최신순이 아닌 이유는, 최신순으로 두면 대기열이
     * 길어질 때 오래 기다린 신청서가 뒤로 밀려 영원히 심사되지 않기 때문이다.
     */
    @Query(value = """
            SELECT * FROM seller.product_submissions
             WHERE status = 'SUBMITTED'
             ORDER BY submitted_at, submission_id
             LIMIT :maxRows OFFSET :skip
            """, nativeQuery = true)
    List<ProductSubmissionJpaEntity> findPending(@Param("maxRows") int maxRows,
                                                 @Param("skip") long skip);

    long countByStatus(String status);
}
