package github.lms.lemuel.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataCashReceiptRepository extends JpaRepository<CashReceiptJpaEntity, Long> {

    /**
     * 유효 자리를 차지하는 건만. 상태 목록은 {@code CashReceiptStatus.occupiesActiveSlot()} 과
     * 마이그레이션의 부분 UNIQUE 인덱스 조건과 <b>같아야</b> 한다 — 셋 중 하나가 어긋나면
     * 화면은 "발급 가능"인데 INSERT 는 제약 위반으로 터진다.
     */
    @Query("SELECT r FROM CashReceiptJpaEntity r WHERE r.paymentId = :paymentId "
            + "AND r.status IN ('REQUESTED', 'ISSUED', 'CANCEL_REQUESTED')")
    Optional<CashReceiptJpaEntity> findActiveByPaymentId(@Param("paymentId") Long paymentId);
}
