package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderStatus;
import github.lms.lemuel.payment.domain.TenderType;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentPersistenceAdapterTest {

    @Mock PaymentJpaRepository paymentJpaRepository;
    @Mock SpringDataPaymentTenderRepository tenderRepository;
    private final PaymentMapper paymentMapper = new PaymentMapper();

    private PaymentPersistenceAdapter adapter() {
        return new PaymentPersistenceAdapter(paymentJpaRepository, paymentMapper, tenderRepository);
    }

    private PaymentJpaEntity entity(Long id, String status) {
        PaymentJpaEntity e = new PaymentJpaEntity();
        e.setId(id);
        e.setOrderId(100L);
        e.setAmount(new BigDecimal("50000"));
        e.setRefundedAmount(BigDecimal.ZERO);
        e.setStatus(status);
        e.setPaymentMethod("CARD");
        e.setPgTransactionId("TOSS:tx-1");
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("loadById: 존재하면 tender 를 hydrate 한 도메인을 반환")
    void loadById_found_hydratesTenders() {
        PaymentPersistenceAdapter adapter = adapter();
        PaymentJpaEntity entity = entity(1L, "CAPTURED");
        when(paymentJpaRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(tenderRepository.findByPaymentIdOrderBySequenceAsc(1L)).thenReturn(List.of());

        Optional<PaymentDomain> result = adapter.loadById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        assertThat(result.get().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("loadById: 존재하지 않으면 empty")
    void loadById_notFound() {
        PaymentPersistenceAdapter adapter = adapter();
        when(paymentJpaRepository.findById(999L)).thenReturn(Optional.empty());

        assertThat(adapter.loadById(999L)).isEmpty();
    }

    @Test
    @DisplayName("loadByIdForUpdate: 비관적 락 조회 위임")
    void loadByIdForUpdate_delegates() {
        PaymentPersistenceAdapter adapter = adapter();
        PaymentJpaEntity entity = entity(1L, "CAPTURED");
        when(paymentJpaRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(entity));
        when(tenderRepository.findByPaymentIdOrderBySequenceAsc(1L)).thenReturn(List.of());

        Optional<PaymentDomain> result = adapter.loadByIdForUpdate(1L);

        assertThat(result).isPresent();
        verify(paymentJpaRepository).findByIdForUpdate(1L);
    }

    @Test
    @DisplayName("loadByOrderId: 주문 ID 로 조회 위임")
    void loadByOrderId_delegates() {
        PaymentPersistenceAdapter adapter = adapter();
        PaymentJpaEntity entity = entity(1L, "READY");
        when(paymentJpaRepository.findByOrderId(100L)).thenReturn(Optional.of(entity));
        when(tenderRepository.findByPaymentIdOrderBySequenceAsc(1L)).thenReturn(List.of());

        Optional<PaymentDomain> result = adapter.loadByOrderId(100L);

        assertThat(result).isPresent();
        assertThat(result.get().getOrderId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("findAllCaptured: CAPTURED 상태만 필터링")
    void findAllCaptured_filtersOnlyCaptured() {
        PaymentPersistenceAdapter adapter = adapter();
        PaymentJpaEntity captured = entity(1L, "CAPTURED");
        PaymentJpaEntity refunded = entity(2L, "REFUNDED");
        when(paymentJpaRepository.findAll()).thenReturn(List.of(captured, refunded));
        when(tenderRepository.findByPaymentIdOrderBySequenceAsc(anyLong())).thenReturn(List.of());

        List<PaymentDomain> result = adapter.findAllCaptured();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("save: 단일결제(비분할)는 tender 저장 없이 payment 만 저장")
    void save_nonSplit_savesOnlyPayment() {
        PaymentPersistenceAdapter adapter = adapter();
        PaymentDomain domain = PaymentDomain.create(100L, new BigDecimal("50000"), "CARD");
        PaymentJpaEntity saved = entity(1L, "READY");
        when(paymentJpaRepository.save(any())).thenReturn(saved);
        when(tenderRepository.findByPaymentIdOrderBySequenceAsc(1L)).thenReturn(List.of());

        PaymentDomain result = adapter.save(domain);

        assertThat(result.getId()).isEqualTo(1L);
        verify(tenderRepository, never()).save(any());
    }

    @Test
    @DisplayName("save: 분할결제 신규 tender(id=null)는 insert 된다")
    void save_split_insertsNewTenders() {
        PaymentPersistenceAdapter adapter = adapter();
        PaymentTender t1 = PaymentTender.newTender(TenderType.POINT, new BigDecimal("5000"), 1);
        PaymentTender t2 = PaymentTender.newTender(TenderType.CARD, new BigDecimal("45000"), 2);
        PaymentDomain domain = PaymentDomain.createWithTenders(100L, List.of(t1, t2), "SPLIT");

        PaymentJpaEntity saved = entity(1L, "READY");
        saved.setAmount(new BigDecimal("50000"));
        when(paymentJpaRepository.save(any())).thenReturn(saved);
        when(tenderRepository.save(any(PaymentTenderJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(tenderRepository.findByPaymentIdOrderBySequenceAsc(1L)).thenReturn(List.of());

        PaymentDomain result = adapter.save(domain);

        assertThat(result.getId()).isEqualTo(1L);
        verify(tenderRepository, times(2)).save(any(PaymentTenderJpaEntity.class));
    }

    @Test
    @DisplayName("save: 분할결제 기존 tender(id 존재)는 update 된다")
    void save_split_updatesExistingTender() {
        PaymentPersistenceAdapter adapter = adapter();
        PaymentTender existingTender = PaymentTender.rehydrate(
                7L, 1L, TenderType.CARD, new BigDecimal("45000"), BigDecimal.ZERO,
                "TOSS:tx-1", TenderStatus.CAPTURED, 2, LocalDateTime.now(), LocalDateTime.now());
        existingTender.addRefund(new BigDecimal("1000"));
        PaymentTender pointTender = PaymentTender.rehydrate(
                8L, 1L, TenderType.POINT, new BigDecimal("5000"), BigDecimal.ZERO,
                null, TenderStatus.CAPTURED, 1, LocalDateTime.now(), LocalDateTime.now());
        PaymentDomain domain = PaymentDomain.rehydrate(1L, 100L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "SPLIT", null, null, null, null);
        domain.replaceTenders(List.of(pointTender, existingTender));

        PaymentJpaEntity saved = entity(1L, "CAPTURED");
        when(paymentJpaRepository.save(any())).thenReturn(saved);
        PaymentTenderJpaEntity existingEntity = new PaymentTenderJpaEntity(
                7L, 1L, TenderType.CARD, new BigDecimal("45000"), BigDecimal.ZERO,
                "TOSS:tx-1", TenderStatus.CAPTURED, 2, LocalDateTime.now(), LocalDateTime.now());
        when(tenderRepository.findById(7L)).thenReturn(Optional.of(existingEntity));
        PaymentTenderJpaEntity pointEntity = new PaymentTenderJpaEntity(
                8L, 1L, TenderType.POINT, new BigDecimal("5000"), BigDecimal.ZERO,
                null, TenderStatus.CAPTURED, 1, LocalDateTime.now(), LocalDateTime.now());
        when(tenderRepository.findById(8L)).thenReturn(Optional.of(pointEntity));
        when(tenderRepository.save(any(PaymentTenderJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenderRepository.findByPaymentIdOrderBySequenceAsc(1L)).thenReturn(List.of());

        adapter.save(domain);

        verify(tenderRepository, times(2)).save(any(PaymentTenderJpaEntity.class));
        verify(tenderRepository).findById(7L);
        verify(tenderRepository).findById(8L);
    }

    @Test
    @DisplayName("save: 분할결제인데 기존 tender 를 찾지 못하면 예외")
    void save_split_missingExistingTenderThrows() {
        PaymentPersistenceAdapter adapter = adapter();
        PaymentTender t1 = PaymentTender.rehydrate(
                7L, 1L, TenderType.CARD, new BigDecimal("45000"), BigDecimal.ZERO,
                "TOSS:tx-1", TenderStatus.CAPTURED, 2, LocalDateTime.now(), LocalDateTime.now());
        PaymentTender t2 = PaymentTender.rehydrate(
                8L, 1L, TenderType.POINT, new BigDecimal("5000"), BigDecimal.ZERO,
                null, TenderStatus.CAPTURED, 1, LocalDateTime.now(), LocalDateTime.now());
        PaymentDomain domain = PaymentDomain.rehydrate(1L, 100L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "SPLIT", null, null, null, null);
        domain.replaceTenders(List.of(t1, t2));

        PaymentJpaEntity saved = entity(1L, "CAPTURED");
        when(paymentJpaRepository.save(any())).thenReturn(saved);
        when(tenderRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.save(domain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tender 사라짐");
    }
}
