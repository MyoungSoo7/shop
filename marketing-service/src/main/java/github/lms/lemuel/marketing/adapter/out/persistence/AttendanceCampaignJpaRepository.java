package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.domain.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface AttendanceCampaignJpaRepository extends JpaRepository<AttendanceCampaignJpaEntity, UUID> {

    List<AttendanceCampaignJpaEntity> findByStatusAndStartsOnLessThanEqualAndEndsOnGreaterThanEqualOrderByStartsOnAscNameAsc(
            CampaignStatus status, LocalDate onForStart, LocalDate onForEnd);

    List<AttendanceCampaignJpaEntity> findAllByOrderByStartsOnDescNameAsc();
}
