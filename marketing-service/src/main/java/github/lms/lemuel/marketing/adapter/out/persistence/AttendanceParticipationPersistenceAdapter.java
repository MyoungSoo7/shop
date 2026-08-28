package github.lms.lemuel.marketing.adapter.out.persistence;

import github.lms.lemuel.marketing.application.port.out.AttendanceAchievementPort;
import github.lms.lemuel.marketing.application.port.out.AttendanceRecordPort;
import github.lms.lemuel.marketing.domain.AttendanceAchievement;
import github.lms.lemuel.marketing.domain.AttendanceRecord;
import github.lms.lemuel.marketing.domain.exception.AlreadyParticipatedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 출석 기록·목표 달성 어댑터.
 *
 * <p>중복 출석을 막는 것은 이 클래스의 조회가 아니라 {@code attendance_records} 의
 * {@code UNIQUE (campaign_id, member_ref, attended_on)} 이다. 레거시는 "오늘 기록이 있나" 를
 * 먼저 조회하고 없으면 INSERT 했는데, 그 사이가 비어 있어서 버튼을 두 번 빠르게 누르면 출석이
 * 두 번 찍히고 포인트도 두 번 나갔다. 인덱스가 막으면 그 틈이 없다.
 */
@Component
class AttendanceParticipationPersistenceAdapter implements AttendanceRecordPort, AttendanceAchievementPort {

    private final AttendanceRecordJpaRepository recordRepository;
    private final AttendanceAchievementJpaRepository achievementRepository;

    AttendanceParticipationPersistenceAdapter(AttendanceRecordJpaRepository recordRepository,
                                              AttendanceAchievementJpaRepository achievementRepository) {
        this.recordRepository = recordRepository;
        this.achievementRepository = achievementRepository;
    }

    @Override
    public List<LocalDate> findAttendedDates(UUID campaignId, String memberRef, LocalDate from, LocalDate to) {
        return findRecords(campaignId, memberRef, from, to).stream().map(AttendanceRecord::attendedOn).toList();
    }

    @Override
    public List<AttendanceRecord> findRecords(UUID campaignId, String memberRef, LocalDate from, LocalDate to) {
        return recordRepository
                .findByCampaignIdAndMemberRefAndAttendedOnBetweenOrderByAttendedOnAsc(campaignId, memberRef, from, to)
                .stream().map(AttendanceRecordJpaEntity::toDomain).toList();
    }

    @Override
    public AttendanceRecord save(AttendanceRecord record) {
        try {
            // saveAndFlush 다 — flush 를 미루면 유니크 위반이 커밋 시점에 터지고, 그때는 이미
            // 보상 요청까지 만들어 둔 뒤라 "중복 출석입니다" 대신 500 이 나간다.
            return recordRepository.saveAndFlush(AttendanceRecordJpaEntity.fromDomain(record)).toDomain();
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyParticipatedException("오늘은 이미 출석했습니다.", e);
        }
    }

    @Override
    public List<AttendanceAchievement> findAchievements(UUID campaignId, String memberRef,
                                                        LocalDate from, LocalDate to) {
        return achievementRepository
                .findByCampaignIdAndMemberRefAndAchievedOnBetweenOrderByAchievedOnAsc(campaignId, memberRef, from, to)
                .stream().map(AttendanceAchievementJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<AttendanceAchievement> saveIfAbsent(AttendanceAchievement achievement) {
        boolean already = !findAchievements(achievement.campaignId(), achievement.memberRef(),
                achievement.achievedOn(), achievement.achievedOn()).isEmpty();
        if (already) {
            return Optional.empty();
        }
        // 여기서 유니크 위반이 나면 삼키지 않고 올린다 — 이유와 재시도가 수렴하는 근거는
        // AttendanceAchievementPort 주석에 있다.
        return Optional.of(
                achievementRepository.saveAndFlush(AttendanceAchievementJpaEntity.fromDomain(achievement)).toDomain());
    }
}
