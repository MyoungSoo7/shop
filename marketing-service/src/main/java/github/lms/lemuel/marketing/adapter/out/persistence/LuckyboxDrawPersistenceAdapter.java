package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.application.port.out.LuckyboxDrawPort;
import github.lms.lemuel.marketing.domain.LuckyboxDraw;
import github.lms.lemuel.marketing.domain.exception.AlreadyParticipatedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 참여 기록 어댑터.
 *
 * <p>참여 제한을 지키는 것은 {@code UNIQUE (campaign_id, member_ref, entry_slot)} 이다.
 * 슬롯 키는 하루 1회면 날짜, 기간 1회면 {@code "ALL"} 이라 같은 인덱스 하나가 두 조건을 다 막는다.
 */
@Component
class LuckyboxDrawPersistenceAdapter implements LuckyboxDrawPort {

    private final LuckyboxDrawJpaRepository repository;

    LuckyboxDrawPersistenceAdapter(LuckyboxDrawJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<LuckyboxDraw> findBySlot(UUID campaignId, String memberRef, String entrySlot) {
        return repository.findByCampaignIdAndMemberRefAndEntrySlot(campaignId, memberRef, entrySlot)
                .map(LuckyboxDrawJpaEntity::toDomain);
    }

    @Override
    public List<LuckyboxDraw> findByMember(UUID campaignId, String memberRef) {
        return repository.findByCampaignIdAndMemberRefOrderByDrawnAtDesc(campaignId, memberRef)
                .stream().map(LuckyboxDrawJpaEntity::toDomain).toList();
    }

    @Override
    public LuckyboxDraw save(LuckyboxDraw draw) {
        try {
            return repository.saveAndFlush(LuckyboxDrawJpaEntity.fromDomain(draw)).toDomain();
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyParticipatedException("이미 참여한 이벤트입니다.", e);
        }
    }
}
