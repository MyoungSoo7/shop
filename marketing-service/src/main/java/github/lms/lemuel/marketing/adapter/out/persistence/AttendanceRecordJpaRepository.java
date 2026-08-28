package github.lms.lemuel.marketing.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface AttendanceRecordJpaRepository extends JpaRepository<AttendanceRecordJpaEntity, UUID> {

    List<AttendanceRecordJpaEntity> findByCampaignIdAndMemberRefAndAttendedOnBetweenOrderByAttendedOnAsc(
            UUID campaignId, String memberRef, LocalDate from, LocalDate to);
}
