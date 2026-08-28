package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.application.port.out.SaveAttendanceCampaignPort;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.CampaignStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 출석 캠페인 적재·저장 어댑터. */
@Component
class AttendanceCampaignPersistenceAdapter implements LoadAttendanceCampaignPort, SaveAttendanceCampaignPort {

    private final AttendanceCampaignJpaRepository repository;

    AttendanceCampaignPersistenceAdapter(AttendanceCampaignJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AttendanceCampaign> findById(UUID campaignId) {
        return repository.findById(campaignId).map(AttendanceCampaignJpaEntity::toDomain);
    }

    @Override
    public List<AttendanceCampaign> findRunningOn(LocalDate on) {
        return repository
                .findByStatusAndStartsOnLessThanEqualAndEndsOnGreaterThanEqualOrderByStartsOnAscNameAsc(
                        CampaignStatus.RUNNING, on, on)
                .stream().map(AttendanceCampaignJpaEntity::toDomain).toList();
    }

    @Override
    public List<AttendanceCampaign> findAllForAdmin() {
        return repository.findAllByOrderByStartsOnDescNameAsc()
                .stream().map(AttendanceCampaignJpaEntity::toDomain).toList();
    }

    @Override
    public AttendanceCampaign save(AttendanceCampaign campaign) {
        // 있으면 갱신, 없으면 신규. 이 분기를 JPA 의 merge 에 맡기지 않는 이유는 @Id 를 우리가
        // 직접 발급하기 때문이다 — Hibernate 는 id 가 있는 객체를 무조건 detached 로 보고
        // SELECT 후 UPDATE 를 돌린다. 신규 저장에서 그 SELECT 는 항상 빈손이다.
        AttendanceCampaignJpaEntity entity = repository.findById(campaign.id())
                .map(found -> {
                    found.sync(campaign);
                    return found;
                })
                .orElseGet(() -> AttendanceCampaignJpaEntity.fromDomain(campaign));
        return repository.save(entity).toDomain();
    }
}
