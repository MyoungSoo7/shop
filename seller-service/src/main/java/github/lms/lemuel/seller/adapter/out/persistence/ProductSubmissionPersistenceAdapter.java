package github.lms.lemuel.seller.adapter.out.persistence;

import github.lms.lemuel.seller.application.port.out.ProductSubmissionPort;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SubmissionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 신청서 원장의 영속화. 도메인 record ↔ 엔티티 변환은 전부 엔티티 안에 있다. */
@Component
@RequiredArgsConstructor
class ProductSubmissionPersistenceAdapter implements ProductSubmissionPort {

    private final ProductSubmissionJpaRepository repository;

    @Override
    public Optional<ProductSubmission> load(long submissionId, long sellerId) {
        return repository.findOwned(submissionId, sellerId).map(ProductSubmissionJpaEntity::toDomain);
    }

    @Override
    public Optional<ProductSubmission> loadAny(long submissionId) {
        return repository.findById(submissionId).map(ProductSubmissionJpaEntity::toDomain);
    }

    /**
     * 신규는 INSERT, 기존은 <b>불러와서 덮어쓴다.</b> {@code from(...)} 으로 만든 엔티티를
     * ID 만 채워 {@code save} 하면 JPA 가 merge 로 처리하면서 감사 컬럼
     * ({@code created_at})을 null 로 덮는다 — {@code @PrePersist} 는 그때 돌지 않는다.
     */
    @Override
    public ProductSubmission save(ProductSubmission submission) {
        if (submission.submissionId() == null) {
            ProductSubmissionJpaEntity saved = repository.save(ProductSubmissionJpaEntity.from(submission));
            return saved.toDomain();
        }
        long submissionId = submission.submissionId();
        ProductSubmissionJpaEntity entity = repository.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException(
                        "저장하려는 신청서가 사라졌습니다: submissionId=" + submissionId));
        entity.apply(submission);
        return repository.save(entity).toDomain();
    }

    @Override
    public long countBySeller(long sellerId, SubmissionStatus status) {
        return status == null
                ? repository.countBySellerId(sellerId)
                : repository.countBySellerIdAndStatus(sellerId, status.name());
    }

    @Override
    public List<ProductSubmission> findBySeller(long sellerId, SubmissionStatus status, int limit, long offset) {
        List<ProductSubmissionJpaEntity> rows = status == null
                ? repository.findBySeller(sellerId, limit, offset)
                : repository.findBySellerAndStatus(sellerId, status.name(), limit, offset);
        return rows.stream().map(ProductSubmissionJpaEntity::toDomain).toList();
    }

    @Override
    public long countPending() {
        return repository.countByStatus(SubmissionStatus.SUBMITTED.name());
    }

    @Override
    public List<ProductSubmission> findPending(int limit, long offset) {
        return repository.findPending(limit, offset).stream()
                .map(ProductSubmissionJpaEntity::toDomain)
                .toList();
    }
}
