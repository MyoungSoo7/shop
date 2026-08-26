package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.LoadGiftClaimPort;
import github.lms.lemuel.order.application.port.out.SaveGiftClaimPort;
import github.lms.lemuel.order.domain.GiftClaim;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class GiftClaimPersistenceAdapter implements SaveGiftClaimPort, LoadGiftClaimPort {

    /** 소멸 배치가 한 번에 가져올 최대치의 상한 — 호출자가 더 큰 값을 줘도 여기서 잘린다. */
    private static final int MAX_EXPIRE_LIMIT = 1000;

    private final SpringDataGiftClaimRepository repository;

    public GiftClaimPersistenceAdapter(SpringDataGiftClaimRepository repository) {
        this.repository = repository;
    }

    @Override
    public GiftClaim save(GiftClaim claim) {
        // detached merge 대신 로드-후-수정. merge 는 매핑에서 빠진 칸을 조용히 null 로 되돌린다.
        GiftClaimJpaEntity entity;
        if (claim.getId() == null) {
            entity = GiftClaimJpaEntity.fromDomain(claim);
        } else {
            entity = repository.findById(claim.getId())
                    .orElseGet(() -> GiftClaimJpaEntity.fromDomain(claim));
            entity.applyFrom(claim);
        }
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<GiftClaim> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTokenHash(tokenHash).map(GiftClaimJpaEntity::toDomain);
    }

    @Override
    public Optional<GiftClaim> findByOrderId(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return repository.findByOrderId(orderId).map(GiftClaimJpaEntity::toDomain);
    }

    @Override
    public List<GiftClaim> findExpirable(LocalDateTime now, int limit) {
        int capped = Math.clamp(limit, 1, MAX_EXPIRE_LIMIT);
        return repository.findExpirable(now, PageRequest.of(0, capped)).stream()
                .map(GiftClaimJpaEntity::toDomain)
                .toList();
    }
}
