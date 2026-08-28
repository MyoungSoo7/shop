package github.lms.lemuel.marketing.adapter.in.web;

import github.lms.lemuel.marketing.application.port.in.CreateAttendanceCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.CreateLuckyboxCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.CreateLuckyboxPrizeCommand;
import github.lms.lemuel.marketing.application.port.in.ManageAttendanceCampaignUseCase;
import github.lms.lemuel.marketing.application.port.in.ManageLuckyboxCampaignUseCase;
import github.lms.lemuel.marketing.application.port.in.UpdateAttendanceCampaignCommand;
import github.lms.lemuel.marketing.application.port.in.UpdateLuckyboxCampaignCommand;
import github.lms.lemuel.marketing.domain.AttendanceCampaign;
import github.lms.lemuel.marketing.domain.BenefitType;
import github.lms.lemuel.marketing.domain.DayTypeRule;
import github.lms.lemuel.marketing.domain.EntryCondition;
import github.lms.lemuel.marketing.domain.LuckyboxCampaign;
import github.lms.lemuel.marketing.domain.LuckyboxPrize;
import github.lms.lemuel.marketing.domain.PeriodType;
import github.lms.lemuel.marketing.domain.PrizeType;
import github.lms.lemuel.marketing.domain.StreakRule;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 마케팅 운영 콘솔 API — ROLE_ADMIN 전용({@code MarketingSecurityConfig}).
 *
 * <p>요청 본문에 {@code actor} 가 없다. 감사에 남는 운영자 이름은 JWT 에서만 꺼낸다 — 본문에서
 * 받으면 누가 이벤트를 열고 경품 확률을 바꿨는지에 대한 기록이 자기신고가 되어 아무 의미가 없다.
 *
 * <p>응답으로 도메인 객체를 그대로 내보내지 않는다. 애그리거트의 접근자는 {@code name()} 형태라
 * Jackson 의 getter 규약과 맞지 않아 그대로 두면 빈 객체가 나가고, 무엇보다 화면에 필요 없는
 * 내부 상태(version 등)까지 계약이 되어 버린다.
 */
@RestController
@RequestMapping("/admin/promotions")
public class AdminPromotionController {

    private final ManageAttendanceCampaignUseCase attendanceUseCase;
    private final ManageLuckyboxCampaignUseCase luckyboxUseCase;

    public AdminPromotionController(ManageAttendanceCampaignUseCase attendanceUseCase,
                                    ManageLuckyboxCampaignUseCase luckyboxUseCase) {
        this.attendanceUseCase = attendanceUseCase;
        this.luckyboxUseCase = luckyboxUseCase;
    }

    // ------------------------------------------------------------------ 출석

    @GetMapping("/attendance")
    public ResponseEntity<List<AttendanceCampaignResponse>> listAttendance() {
        return ResponseEntity.ok(attendanceUseCase.list().stream().map(AttendanceCampaignResponse::from).toList());
    }

    @GetMapping("/attendance/{campaignId}")
    public ResponseEntity<AttendanceCampaignResponse> getAttendance(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(AttendanceCampaignResponse.from(attendanceUseCase.get(campaignId)));
    }

    @PostMapping("/attendance")
    public ResponseEntity<Map<String, UUID>> createAttendance(@RequestBody AttendanceCampaignRequest request) {
        UUID id = attendanceUseCase.create(new CreateAttendanceCampaignCommand(
                request.tenantRef(), request.name(), request.periodType(), request.startsOn(), request.endsOn(),
                request.streakRule(), request.requiredCount(), request.dayTypeRule(), request.dailyRewardPoints(),
                request.goalRewardPoints(), request.rewardExpiresFrom(), request.rewardExpiresOn(),
                request.pcImageUrl(), request.mobileImageUrl(), request.messageBeforeStart(),
                request.messageRunning(), request.messageAchieved(), request.messageClosed(),
                CurrentMember.actor()));
        return ResponseEntity.ok(Map.of("campaignId", id));
    }

    @PutMapping("/attendance/{campaignId}")
    public ResponseEntity<Void> updateAttendance(@PathVariable UUID campaignId,
                                                 @RequestBody AttendanceCampaignRequest request) {
        attendanceUseCase.update(new UpdateAttendanceCampaignCommand(
                campaignId, request.name(), request.startsOn(), request.endsOn(), request.dailyRewardPoints(),
                request.goalRewardPoints(), request.pcImageUrl(), request.mobileImageUrl(),
                request.messageBeforeStart(), request.messageRunning(), request.messageAchieved(),
                request.messageClosed(), CurrentMember.actor()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/attendance/{campaignId}/open")
    public ResponseEntity<Void> openAttendance(@PathVariable UUID campaignId) {
        attendanceUseCase.open(campaignId, CurrentMember.actor());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/attendance/{campaignId}/close")
    public ResponseEntity<Void> closeAttendance(@PathVariable UUID campaignId) {
        attendanceUseCase.close(campaignId, CurrentMember.actor());
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------- 럭키박스

    @GetMapping("/luckybox")
    public ResponseEntity<List<LuckyboxCampaignResponse>> listLuckybox() {
        return ResponseEntity.ok(luckyboxUseCase.list().stream().map(LuckyboxCampaignResponse::from).toList());
    }

    @GetMapping("/luckybox/{campaignId}")
    public ResponseEntity<LuckyboxCampaignResponse> getLuckybox(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(LuckyboxCampaignResponse.from(luckyboxUseCase.get(campaignId)));
    }

    @PostMapping("/luckybox")
    public ResponseEntity<Map<String, UUID>> createLuckybox(@RequestBody LuckyboxCampaignRequest request) {
        UUID id = luckyboxUseCase.create(new CreateLuckyboxCampaignCommand(
                request.tenantRef(), request.name(), request.startsOn(), request.endsOn(), request.benefitType(),
                request.benefitOn(), request.entryCondition(), request.rewardExpiresOn(), request.note(),
                request.pcImageUrl(), request.mobileImageUrl(), CurrentMember.actor()));
        return ResponseEntity.ok(Map.of("campaignId", id));
    }

    @PutMapping("/luckybox/{campaignId}")
    public ResponseEntity<Void> updateLuckybox(@PathVariable UUID campaignId,
                                               @RequestBody LuckyboxCampaignRequest request) {
        luckyboxUseCase.update(new UpdateLuckyboxCampaignCommand(
                campaignId, request.name(), request.startsOn(), request.endsOn(), request.benefitOn(),
                request.rewardExpiresOn(), request.note(), request.pcImageUrl(), request.mobileImageUrl(),
                CurrentMember.actor()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/luckybox/{campaignId}/open")
    public ResponseEntity<Void> openLuckybox(@PathVariable UUID campaignId) {
        luckyboxUseCase.open(campaignId, CurrentMember.actor());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/luckybox/{campaignId}/close")
    public ResponseEntity<Void> closeLuckybox(@PathVariable UUID campaignId) {
        luckyboxUseCase.close(campaignId, CurrentMember.actor());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/luckybox/{campaignId}/prizes")
    public ResponseEntity<List<LuckyboxPrizeResponse>> prizes(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(luckyboxUseCase.prizes(campaignId).stream()
                .map(LuckyboxPrizeResponse::from).toList());
    }

    @PostMapping("/luckybox/{campaignId}/prizes")
    public ResponseEntity<Map<String, UUID>> addPrize(@PathVariable UUID campaignId,
                                                      @RequestBody LuckyboxPrizeRequest request) {
        UUID id = luckyboxUseCase.addPrize(new CreateLuckyboxPrizeCommand(
                campaignId, request.prizeType(), request.rewardPoints(), request.textReward(),
                request.totalQuota(), request.dailyQuota(), request.winRate(), request.displayOrder(),
                CurrentMember.actor()));
        return ResponseEntity.ok(Map.of("prizeId", id));
    }

    /**
     * 경품을 추첨에서 뺀다.
     *
     * <p>DELETE 지만 행은 지우지 않는다 — 이미 당첨된 사람의 기록이 이 경품을 참조하고 있어서,
     * 지우면 그 사람의 당첨 내역이 "알 수 없는 경품" 이 된다. 자세한 건 서비스 주석에 있다.
     */
    @DeleteMapping("/luckybox/prizes/{prizeId}")
    public ResponseEntity<Void> deactivatePrize(@PathVariable UUID prizeId) {
        luckyboxUseCase.deactivatePrize(prizeId, CurrentMember.actor());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- 계약

    /** 등록·수정 공용 본문. 수정에서 무시되는 필드는 도메인이 걸러 낸다(규칙은 바꿀 수 없다). */
    public record AttendanceCampaignRequest(
            String tenantRef, String name, PeriodType periodType, LocalDate startsOn, LocalDate endsOn,
            StreakRule streakRule, int requiredCount, DayTypeRule dayTypeRule, BigDecimal dailyRewardPoints,
            BigDecimal goalRewardPoints, LocalDate rewardExpiresFrom, LocalDate rewardExpiresOn,
            String pcImageUrl, String mobileImageUrl, String messageBeforeStart, String messageRunning,
            String messageAchieved, String messageClosed) {
    }

    /**
     * 등록·수정 공용 본문.
     *
     * <p>{@code entryCondition} 은 참여 <b>빈도</b>({@code PER_DAY}/{@code PER_PERIOD})다. 참여
     * <b>대상</b>을 고르는 필드는 없다 — 가입일·주문금액·배송상태 조건이 왜 빠졌는지는
     * {@code docs/plan/marketing-legacy-gap.md} §2 ④ 에 있다.
     */
    public record LuckyboxCampaignRequest(
            String tenantRef, String name, LocalDate startsOn, LocalDate endsOn, BenefitType benefitType,
            LocalDate benefitOn, EntryCondition entryCondition, LocalDate rewardExpiresOn, String note,
            String pcImageUrl, String mobileImageUrl) {
    }

    public record LuckyboxPrizeRequest(
            PrizeType prizeType, BigDecimal rewardPoints, String textReward, Integer totalQuota,
            Integer dailyQuota, BigDecimal winRate, int displayOrder) {
    }

    public record AttendanceCampaignResponse(
            UUID id, String name, String status, String periodType, LocalDate startsOn, LocalDate endsOn,
            String streakRule, int requiredCount, String dayTypeRule, BigDecimal dailyRewardPoints,
            BigDecimal goalRewardPoints, LocalDate rewardExpiresFrom, LocalDate rewardExpiresOn,
            String pcImageUrl, String mobileImageUrl, String createdBy, String updatedBy) {

        static AttendanceCampaignResponse from(AttendanceCampaign c) {
            return new AttendanceCampaignResponse(c.id(), c.name(), c.status().name(), c.periodType().name(),
                    c.startsOn(), c.endsOn(), c.streakRule().name(), c.requiredCount(), c.dayTypeRule().name(),
                    c.dailyRewardPoints(), c.goalRewardPoints(), c.rewardExpiresFrom(), c.rewardExpiresOn(),
                    c.banner().pcImageUrl(), c.banner().mobileImageUrl(), c.createdBy(), c.updatedBy());
        }
    }

    public record LuckyboxCampaignResponse(
            UUID id, String name, String status, LocalDate startsOn, LocalDate endsOn, String benefitType,
            LocalDate benefitOn, String entryCondition, LocalDate rewardExpiresOn, String note,
            String pcImageUrl, String mobileImageUrl, String createdBy, String updatedBy) {

        static LuckyboxCampaignResponse from(LuckyboxCampaign c) {
            return new LuckyboxCampaignResponse(c.id(), c.name(), c.status().name(), c.startsOn(), c.endsOn(),
                    c.benefitType().name(), c.benefitOn(), c.entryCondition().name(), c.rewardExpiresOn(),
                    c.note(), c.banner().pcImageUrl(), c.banner().mobileImageUrl(), c.createdBy(), c.updatedBy());
        }
    }

    /**
     * 운영자용 경품 응답 — 고객용 {@code LuckyboxPrizeView} 와 달리 확률과 소진 현황을 보여 준다.
     * 고객에게 확률을 노출하지 않는 이유는 그 DTO 주석에 있다.
     */
    public record LuckyboxPrizeResponse(
            UUID id, String prizeType, BigDecimal rewardPoints, String textReward, Integer totalQuota,
            Integer dailyQuota, BigDecimal winRate, int issuedCount, boolean active, int displayOrder) {

        static LuckyboxPrizeResponse from(LuckyboxPrize p) {
            return new LuckyboxPrizeResponse(p.id(), p.prizeType().name(), p.rewardPoints(), p.textReward(),
                    p.totalQuota(), p.dailyQuota(), p.winRate(), p.issuedCount(), p.active(), p.displayOrder());
        }
    }
}
