package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.application.port.out.LuckyboxPrizePort;
import github.lms.lemuel.marketing.domain.LuckyboxPrize;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 경품 적재·저장·수량 예약 어댑터. */
@Component
class LuckyboxPrizePersistenceAdapter implements LuckyboxPrizePort {

    private final LuckyboxPrizeJpaRepository repository;

    LuckyboxPrizePersistenceAdapter(LuckyboxPrizeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<LuckyboxPrize> findByCampaign(UUID campaignId) {
        return repository.findByCampaignIdOrderByDisplayOrderAsc(campaignId)
                .stream().map(LuckyboxPrizeJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<LuckyboxPrize> findById(UUID prizeId) {
        return repository.findById(prizeId).map(LuckyboxPrizeJpaEntity::toDomain);
    }

    @Override
    public LuckyboxPrize save(LuckyboxPrize prize) {
        // 기존 행이면 설정값만 덮어쓴다. 수량 카운터를 왜 빼는지는 LuckyboxPrizeJpaEntity 주석에 있다.
        LuckyboxPrizeJpaEntity entity = repository.findById(prize.id())
                .map(found -> {
                    found.sync(prize);
                    return found;
                })
                .orElseGet(() -> LuckyboxPrizeJpaEntity.fromDomain(prize));
        return repository.save(entity).toDomain();
    }

    @Override
    public boolean tryReserve(UUID prizeId, LocalDate on) {
        return repository.tryReserve(prizeId, on) == 1;
    }
}
