package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.CashReceiptPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;
import github.lms.lemuel.payment.domain.CashReceiptStatus;
import github.lms.lemuel.payment.domain.exception.DuplicateCashReceiptException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 현금영수증 영속 어댑터.
 *
 * <p>동시 발급 신청은 애플리케이션 조회로 막을 수 없다(둘 다 "없음"을 보고 통과). 최종 방어선은
 * 부분 UNIQUE 인덱스이고, 여기서는 그 제약 위반을 <b>도메인 언어</b>로 번역해 올린다 —
 * 사용자에게 500 이 아니라 "이미 발급된 영수증이 있습니다"가 가야 한다.
 */
@Component
public class CashReceiptPersistenceAdapter implements CashReceiptPort {

    private final SpringDataCashReceiptRepository repository;

    public CashReceiptPersistenceAdapter(SpringDataCashReceiptRepository repository) {
        this.repository = repository;
    }

    @Override
    public CashReceipt save(CashReceipt receipt) {
        try {
            CashReceiptJpaEntity saved = repository.save(toEntity(receipt));
            receipt.assignId(saved.getId());
            return toDomain(saved);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateCashReceiptException("이미 발급된 현금영수증이 있습니다: paymentId="
                    + receipt.getPaymentId());
        }
    }

    @Override
    public Optional<CashReceipt> findById(Long id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<CashReceipt> findActiveByPaymentId(Long paymentId) {
        return repository.findActiveByPaymentId(paymentId).map(this::toDomain);
    }

    private CashReceiptJpaEntity toEntity(CashReceipt domain) {
        CashReceiptJpaEntity entity = new CashReceiptJpaEntity();
        entity.setId(domain.getId());
        entity.setPaymentId(domain.getPaymentId());
        entity.setOrderId(domain.getOrderId());
        entity.setUserId(domain.getUserId());
        entity.setPurpose(domain.getPurpose().name());
        entity.setIdentifierType(domain.getIdentifier().getType().name());
        entity.setIdentifierValue(domain.getIdentifier().getValue());
        entity.setTotalAmount(domain.getTotalAmount());
        entity.setSupplyAmount(domain.getSupplyAmount());
        entity.setVatAmount(domain.getVatAmount());
        entity.setStatus(domain.getStatus().name());
        entity.setApprovalNumber(domain.getApprovalNumber());
        entity.setFailureReason(domain.getFailureReason());
        entity.setIssuedAt(domain.getIssuedAt());
        entity.setCanceledAt(domain.getCanceledAt());
        entity.setCancelReason(domain.getCancelReason());
        entity.setRequestedAt(domain.getRequestedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private CashReceipt toDomain(CashReceiptJpaEntity entity) {
        return CashReceipt.rehydrate(
                entity.getId(),
                entity.getPaymentId(),
                entity.getOrderId(),
                entity.getUserId(),
                CashReceiptPurpose.valueOf(entity.getPurpose()),
                CashReceiptIdentifier.restore(
                        CashReceiptIdentifier.Type.valueOf(entity.getIdentifierType()),
                        entity.getIdentifierValue()),
                entity.getTotalAmount(),
                entity.getSupplyAmount(),
                entity.getVatAmount(),
                CashReceiptStatus.valueOf(entity.getStatus()),
                entity.getApprovalNumber(),
                entity.getFailureReason(),
                entity.getIssuedAt(),
                entity.getCanceledAt(),
                entity.getCancelReason(),
                entity.getRequestedAt(),
                entity.getUpdatedAt());
    }
}
