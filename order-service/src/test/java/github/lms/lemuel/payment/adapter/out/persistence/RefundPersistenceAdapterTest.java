package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.Refund;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundPersistenceAdapterTest {

    @Mock SpringDataRefundJpaRepository repository;

    private RefundPersistenceAdapter adapter() {
        return new RefundPersistenceAdapter(repository);
    }

    private RefundJpaEntity entity(Long id, String status) {
        RefundJpaEntity e = new RefundJpaEntity();
        e.setId(id);
        e.setPaymentId(1L);
        e.setAmount(new BigDecimal("5000"));
        e.setStatus(status);
        e.setReason("고객 요청");
        e.setIdempotencyKey("key-1");
        e.setRetryCount(0);
        e.setRequestedAt(LocalDateTime.now());
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("findByPaymentIdAndIdempotencyKey: 존재하면 도메인으로 매핑")
    void findByPaymentIdAndIdempotencyKey_found() {
        when(repository.findByPaymentIdAndIdempotencyKey(1L, "key-1"))
                .thenReturn(Optional.of(entity(1L, "COMPLETED")));

        Optional<Refund> result = adapter().findByPaymentIdAndIdempotencyKey(1L, "key-1");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(Refund.Status.COMPLETED);
        assertThat(result.get().getAmount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("findByPaymentIdAndIdempotencyKey: 없으면 empty")
    void findByPaymentIdAndIdempotencyKey_notFound() {
        when(repository.findByPaymentIdAndIdempotencyKey(1L, "missing"))
                .thenReturn(Optional.empty());

        assertThat(adapter().findByPaymentIdAndIdempotencyKey(1L, "missing")).isEmpty();
    }

    @Test
    @DisplayName("findById: 존재하면 도메인 반환")
    void findById_found() {
        when(repository.findById(9L)).thenReturn(Optional.of(entity(9L, "FAILED")));

        Optional<Refund> result = adapter().findById(9L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("findAllByPaymentId: 요청시각 역순 목록 매핑")
    void findAllByPaymentId_mapsList() {
        when(repository.findByPaymentIdOrderByRequestedAtDesc(1L))
                .thenReturn(List.of(entity(2L, "COMPLETED"), entity(1L, "FAILED")));

        List<Refund> result = adapter().findAllByPaymentId(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("findRetryable: FAILED + 재시도시각 도래 건 매핑")
    void findRetryable_mapsList() {
        LocalDateTime now = LocalDateTime.now();
        when(repository.findByStatusAndNextRetryAtLessThanEqual("FAILED", now))
                .thenReturn(List.of(entity(3L, "FAILED")));

        List<Refund> result = adapter().findRetryable(now);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(Refund.Status.FAILED);
    }

    @Test
    @DisplayName("findByStatus: 상태별 최신순 목록 매핑")
    void findByStatus_mapsList() {
        when(repository.findByStatusOrderByUpdatedAtDesc("FAILED"))
                .thenReturn(List.of(entity(4L, "FAILED")));

        List<Refund> result = adapter().findByStatus(Refund.Status.FAILED);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("save: 도메인→엔티티→도메인 왕복 매핑 보존")
    void save_roundTripsAllFields() {
        Refund refund = Refund.request(1L, new BigDecimal("7000"), "key-x", "고객 변심");
        refund.assignId(5L);

        when(repository.save(any(RefundJpaEntity.class))).thenAnswer(inv -> {
            RefundJpaEntity e = inv.getArgument(0);
            return e;
        });

        Refund saved = adapter().save(refund);

        assertThat(saved.getId()).isEqualTo(5L);
        assertThat(saved.getPaymentId()).isEqualTo(1L);
        assertThat(saved.getAmount()).isEqualByComparingTo("7000");
        assertThat(saved.getIdempotencyKey()).isEqualTo("key-x");
        assertThat(saved.getReason()).isEqualTo("고객 변심");
        assertThat(saved.getStatus()).isEqualTo(Refund.Status.REQUESTED);
    }
}
