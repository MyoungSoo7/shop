package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.TenderStatus;
import github.lms.lemuel.payment.domain.TenderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentTenderJpaEntity 매핑/라이프사이클 콜백 회귀 테스트.
 */
class PaymentTenderJpaEntityTest {

    @Test
    @DisplayName("전체 인자 생성자 + getter 왕복")
    void fullArgsConstructor_roundTrip() {
        LocalDateTime now = LocalDateTime.now();
        PaymentTenderJpaEntity entity = new PaymentTenderJpaEntity(
                1L, 10L, TenderType.CARD, new BigDecimal("35000"),
                new BigDecimal("5000"), "TOSS:tx-1", TenderStatus.CAPTURED,
                3, now, now);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getPaymentId()).isEqualTo(10L);
        assertThat(entity.getTenderType()).isEqualTo(TenderType.CARD);
        assertThat(entity.getAmount()).isEqualByComparingTo("35000");
        assertThat(entity.getRefundedAmount()).isEqualByComparingTo("5000");
        assertThat(entity.getPgTransactionId()).isEqualTo("TOSS:tx-1");
        assertThat(entity.getStatus()).isEqualTo(TenderStatus.CAPTURED);
        assertThat(entity.getSequence()).isEqualTo(3);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("onCreate: createdAt/updatedAt 이 비어있으면 현재 시각으로 채워진다")
    void onCreate_fillsTimestampsWhenNull() {
        PaymentTenderJpaEntity entity = new PaymentTenderJpaEntity(
                null, 10L, TenderType.POINT, new BigDecimal("5000"),
                BigDecimal.ZERO, null, TenderStatus.PENDING, 1, null, null);

        entity.onCreate();

        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("onCreate: 이미 값이 있으면 덮어쓰지 않는다")
    void onCreate_preservesExistingTimestamps() {
        LocalDateTime fixed = LocalDateTime.of(2026, 1, 1, 0, 0);
        PaymentTenderJpaEntity entity = new PaymentTenderJpaEntity(
                null, 10L, TenderType.POINT, new BigDecimal("5000"),
                BigDecimal.ZERO, null, TenderStatus.PENDING, 1, fixed, fixed);

        entity.onCreate();

        assertThat(entity.getCreatedAt()).isEqualTo(fixed);
        assertThat(entity.getUpdatedAt()).isEqualTo(fixed);
    }

    @Test
    @DisplayName("onUpdate: updatedAt 을 현재 시각으로 갱신한다")
    void onUpdate_refreshesUpdatedAt() {
        LocalDateTime fixed = LocalDateTime.of(2020, 1, 1, 0, 0);
        PaymentTenderJpaEntity entity = new PaymentTenderJpaEntity(
                1L, 10L, TenderType.CARD, new BigDecimal("10000"),
                BigDecimal.ZERO, "TOSS:tx-1", TenderStatus.CAPTURED, 1, fixed, fixed);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(fixed);
    }

    @Test
    @DisplayName("applyState: 환불누적/거래ID/상태/시각을 갱신한다")
    void applyState_updatesMutableFields() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 0, 0);
        PaymentTenderJpaEntity entity = new PaymentTenderJpaEntity(
                1L, 10L, TenderType.CARD, new BigDecimal("10000"),
                BigDecimal.ZERO, "TOSS:tx-1", TenderStatus.CAPTURED, 1, created, created);
        LocalDateTime updated = LocalDateTime.of(2026, 6, 1, 0, 0);

        entity.applyState(new BigDecimal("3000"), "TOSS:tx-1-refund", TenderStatus.REFUNDED, updated);

        assertThat(entity.getRefundedAmount()).isEqualByComparingTo("3000");
        assertThat(entity.getPgTransactionId()).isEqualTo("TOSS:tx-1-refund");
        assertThat(entity.getStatus()).isEqualTo(TenderStatus.REFUNDED);
        assertThat(entity.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    @DisplayName("protected no-args 생성자는 프레임워크(JPA)용으로 필드가 비어있다")
    void noArgsConstructor_startsEmpty() {
        PaymentTenderJpaEntity entity = new PaymentTenderJpaEntity();

        assertThat(entity.getId()).isNull();
        assertThat(entity.getPaymentId()).isNull();
        assertThat(entity.getTenderType()).isNull();
    }
}
