package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;
import github.lms.lemuel.payment.domain.CashReceiptStatus;
import github.lms.lemuel.payment.domain.exception.DuplicateCashReceiptException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 현금영수증 영속 어댑터 단위 테스트.
 *
 * <p>어댑터의 책임은 두 가지뿐이다 — <b>도메인↔엔티티 매핑</b>과 <b>DB 제약 위반의 도메인 번역</b>.
 * 둘 다 DB 없이 검증할 수 있고, DB 가 필요한 부분(부분 UNIQUE 인덱스가 실제로 잡는지)은
 * 통합 테스트의 몫이다. 여기서는 리포지토리를 목으로 두고 매핑이 <b>왕복해도 값이 보존되는지</b>와,
 * 제약 위반이 500 이 아니라 도메인 예외로 올라가는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class CashReceiptPersistenceAdapterTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 8, 22, 10, 0);
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 8, 22, 10, 1);

    @Mock SpringDataCashReceiptRepository repository;

    private CashReceiptPersistenceAdapter adapter() {
        return new CashReceiptPersistenceAdapter(repository);
    }

    /** 발급 완료(ISSUED) 상태의 소득공제 영수증 — 모든 nullable 필드가 채워진 최대 케이스. */
    private CashReceipt issuedDomain(Long id) {
        return CashReceipt.rehydrate(
                id, 100L, 200L, 7L,
                CashReceiptPurpose.INCOME_DEDUCTION,
                CashReceiptIdentifier.restore(CashReceiptIdentifier.Type.MOBILE, "01012345678"),
                new BigDecimal("11000"), new BigDecimal("10000"), new BigDecimal("1000"),
                CashReceiptStatus.ISSUED, "APPROVAL-1", null,
                ISSUED_AT, null, null, REQUESTED_AT, ISSUED_AT);
    }

    private CashReceiptJpaEntity issuedEntity(Long id) {
        CashReceiptJpaEntity e = new CashReceiptJpaEntity();
        e.setId(id);
        e.setPaymentId(100L);
        e.setOrderId(200L);
        e.setUserId(7L);
        e.setPurpose("INCOME_DEDUCTION");
        e.setIdentifierType("MOBILE");
        e.setIdentifierValue("01012345678");
        e.setTotalAmount(new BigDecimal("11000"));
        e.setSupplyAmount(new BigDecimal("10000"));
        e.setVatAmount(new BigDecimal("1000"));
        e.setStatus("ISSUED");
        e.setApprovalNumber("APPROVAL-1");
        e.setFailureReason(null);
        e.setIssuedAt(ISSUED_AT);
        e.setCanceledAt(null);
        e.setCancelReason(null);
        e.setRequestedAt(REQUESTED_AT);
        e.setUpdatedAt(ISSUED_AT);
        return e;
    }

    @Test
    @DisplayName("save: 도메인의 모든 필드를 엔티티로 옮긴다 — 하나라도 빠지면 DB 에만 없는 값이 생긴다")
    void save_mapsEveryFieldToEntity() {
        when(repository.save(any())).thenAnswer(inv -> {
            CashReceiptJpaEntity arg = inv.getArgument(0);
            arg.setId(55L);
            return arg;
        });

        adapter().save(issuedDomain(null));

        ArgumentCaptor<CashReceiptJpaEntity> captor = ArgumentCaptor.forClass(CashReceiptJpaEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        CashReceiptJpaEntity saved = captor.getValue();
        assertThat(saved.getPaymentId()).isEqualTo(100L);
        assertThat(saved.getOrderId()).isEqualTo(200L);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getPurpose()).isEqualTo("INCOME_DEDUCTION");
        assertThat(saved.getIdentifierType()).isEqualTo("MOBILE");
        assertThat(saved.getIdentifierValue()).isEqualTo("01012345678");
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("11000");
        assertThat(saved.getSupplyAmount()).isEqualByComparingTo("10000");
        assertThat(saved.getVatAmount()).isEqualByComparingTo("1000");
        assertThat(saved.getStatus()).isEqualTo("ISSUED");
        assertThat(saved.getApprovalNumber()).isEqualTo("APPROVAL-1");
        assertThat(saved.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(saved.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(saved.getUpdatedAt()).isEqualTo(ISSUED_AT);
    }

    @Test
    @DisplayName("save: DB 가 채운 id 를 도메인에 되돌려 주고, 저장된 값을 도메인으로 복원해 반환한다")
    void save_assignsGeneratedIdAndReturnsDomain() {
        when(repository.save(any())).thenAnswer(inv -> {
            CashReceiptJpaEntity arg = inv.getArgument(0);
            arg.setId(55L);
            return arg;
        });
        CashReceipt domain = issuedDomain(null);

        CashReceipt result = adapter().save(domain);

        assertThat(domain.getId()).isEqualTo(55L);   // 호출자가 들고 있던 인스턴스에도 반영된다
        assertThat(result.getId()).isEqualTo(55L);
        assertThat(result.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
        assertThat(result.getPurpose()).isEqualTo(CashReceiptPurpose.INCOME_DEDUCTION);
        assertThat(result.getIdentifier().getType()).isEqualTo(CashReceiptIdentifier.Type.MOBILE);
        assertThat(result.getTotalAmount()).isEqualByComparingTo("11000");
    }

    @Test
    @DisplayName("save: 부분 UNIQUE 위반은 500 이 아니라 '이미 발급됨' 도메인 예외로 번역한다")
    void save_translatesConstraintViolation() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> adapter().save(issuedDomain(null)))
                .isInstanceOf(DuplicateCashReceiptException.class)
                .hasMessageContaining("paymentId=100");
    }

    @Test
    @DisplayName("findById: 있으면 도메인으로 매핑, 없으면 empty")
    void findById() {
        when(repository.findById(55L)).thenReturn(Optional.of(issuedEntity(55L)));
        when(repository.findById(999L)).thenReturn(Optional.empty());

        Optional<CashReceipt> found = adapter().findById(55L);
        assertThat(found).isPresent();
        assertThat(found.get().getPaymentId()).isEqualTo(100L);
        assertThat(found.get().getIdentifier().getValue()).isEqualTo("01012345678");

        assertThat(adapter().findById(999L)).isEmpty();
    }

    @Test
    @DisplayName("findActiveByPaymentId: 있으면 도메인으로 매핑, 없으면 empty")
    void findActiveByPaymentId() {
        when(repository.findActiveByPaymentId(100L)).thenReturn(Optional.of(issuedEntity(55L)));
        when(repository.findActiveByPaymentId(404L)).thenReturn(Optional.empty());

        Optional<CashReceipt> found = adapter().findActiveByPaymentId(100L);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(55L);

        assertThat(adapter().findActiveByPaymentId(404L)).isEmpty();
    }

    @Test
    @DisplayName("취소된 영수증도 취소 필드까지 왕복 보존한다 (nullable 분기의 반대편)")
    void mapsCanceledFields() {
        CashReceiptJpaEntity canceled = issuedEntity(56L);
        canceled.setStatus("CANCELED");
        canceled.setCanceledAt(LocalDateTime.of(2026, 8, 22, 12, 0));
        canceled.setCancelReason("고객 요청");
        canceled.setFailureReason("국세청 반려");
        when(repository.findById(56L)).thenReturn(Optional.of(canceled));

        CashReceipt result = adapter().findById(56L).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(CashReceiptStatus.CANCELED);
        assertThat(result.getCanceledAt()).isEqualTo(LocalDateTime.of(2026, 8, 22, 12, 0));
        assertThat(result.getCancelReason()).isEqualTo("고객 요청");
        assertThat(result.getFailureReason()).isEqualTo("국세청 반려");
    }

    @Test
    @DisplayName("지출증빙(사업자등록번호) 영수증도 용도·식별번호 조합 그대로 복원한다")
    void mapsExpenseProof() {
        CashReceiptJpaEntity expense = issuedEntity(57L);
        expense.setPurpose("EXPENSE_PROOF");
        expense.setIdentifierType("BUSINESS_NUMBER");
        expense.setIdentifierValue("1234567890");
        when(repository.findById(57L)).thenReturn(Optional.of(expense));

        CashReceipt result = adapter().findById(57L).orElseThrow();

        assertThat(result.getPurpose()).isEqualTo(CashReceiptPurpose.EXPENSE_PROOF);
        assertThat(result.getIdentifier().getType()).isEqualTo(CashReceiptIdentifier.Type.BUSINESS_NUMBER);
        assertThat(result.getIdentifier().getValue()).isEqualTo("1234567890");
    }
}
