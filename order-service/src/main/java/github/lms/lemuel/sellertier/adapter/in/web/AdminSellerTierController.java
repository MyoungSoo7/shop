package github.lms.lemuel.sellertier.adapter.in.web;

import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase;
import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase.TierEvaluationReport;
import github.lms.lemuel.sellertier.application.port.in.CheckSellerTierIntegrityUseCase;
import github.lms.lemuel.sellertier.application.port.in.CheckSellerTierIntegrityUseCase.TierIntegrityReport;
import github.lms.lemuel.sellertier.application.port.in.OverrideSellerTierUseCase;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.SellerTierPolicy;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;

/**
 * 셀러 등급 운영 콘솔 (ADR 0031).
 *
 * <pre>
 *   POST /admin/seller-tiers/evaluate                 미리보기(무변경)
 *   POST /admin/seller-tiers/evaluate?dryRun=false    실제 재산정
 *   POST /admin/seller-tiers/{sellerId}/override      관리자 지정(사유 필수)
 *   GET  /admin/seller-tiers/integrity                정본↔캐시 정합 검사(읽기 전용)
 *   GET  /admin/seller-tiers/policy                   적용 중인 임계 확인
 * </pre>
 *
 * <p>등급 하나가 수수료율·정산주기·홀드백을 동시에 바꾸므로 <b>미리보기가 기본값</b>이다 —
 * 파라미터를 빠뜨린 호출이 곧바로 전 셀러 등급을 바꾸면 안 된다.
 *
 * <p>{@code /policy} 는 현재 적용 중인 임계를 그대로 보여준다. 임계는 설정값이라 배포 환경마다
 * 다를 수 있고, "지금 무슨 기준으로 도는가"를 확인할 방법이 없으면 미리보기 결과를 해석할 수 없다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/seller-tiers/**} 매처(ADMIN)로 제한된다 —
 * 등급은 정산 금액을 바꾸므로 조회 콘솔과 달리 MANAGER 에게 열지 않는다.
 */
@RestController
@RequestMapping("/admin/seller-tiers")
public class AdminSellerTierController {

    private static final int DEFAULT_LIMIT = 1000;
    private static final int DEFAULT_SAMPLE_LIMIT = 50;

    private final EvaluateSellerTiersUseCase useCase;
    private final OverrideSellerTierUseCase overrideUseCase;
    private final CheckSellerTierIntegrityUseCase integrityUseCase;
    private final SellerTierPolicy policy;

    public AdminSellerTierController(EvaluateSellerTiersUseCase useCase,
                                     OverrideSellerTierUseCase overrideUseCase,
                                     CheckSellerTierIntegrityUseCase integrityUseCase,
                                     SellerTierPolicy policy) {
        this.useCase = useCase;
        this.overrideUseCase = overrideUseCase;
        this.integrityUseCase = integrityUseCase;
        this.policy = policy;
    }

    @Operation(summary = "등급 재산정 — dryRun 기본 true",
            description = "실제 반영은 dryRun=false 를 명시해야 한다. 행별로 이전·이후 등급과 근거 거래액을 낸다.")
    @PostMapping("/evaluate")
    public ResponseEntity<TierEvaluationReport> evaluate(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok(
                useCase.evaluate(date == null ? LocalDate.now() : date, dryRun, limit));
    }

    @Operation(summary = "관리자 등급 지정 — 사유 필수, 보호 기간이 함께 걸린다",
            description = "자동 판정으로 담을 수 없는 사정(계약·보상·합의)을 반영한다. "
                    + "지정에는 강등 유예가 따라붙어 다음 재산정이 곧바로 되돌리지 못한다.")
    @PostMapping("/{sellerId}/override")
    public ResponseEntity<AssignmentView> override(@PathVariable("sellerId") Long sellerId,
                                                   @Valid @RequestBody OverrideRequest request,
                                                   Principal principal) {
        // 지정자는 요청 본문이 아니라 인증 주체에서 딴다 — 감사 이력의 작성자를 호출자가 정하게 두면
        // 남의 이름으로 등급을 바꿀 수 있다.
        String changedBy = principal == null ? "admin" : principal.getName();
        TierAssignment assignment = overrideUseCase.override(
                sellerId, request.tier(), request.memo(), changedBy, LocalDate.now());
        return ResponseEntity.ok(AssignmentView.of(assignment));
    }

    @Operation(summary = "등급 캐시 정합 검사 — 정본과 users.seller_tier 의 불일치",
            description = "읽기 전용. 불일치 상태로 결제가 일어나면 그 시점 캐시값이 이벤트에 실려 정산이 "
                    + "확정되고, 정산은 스냅샷이라 사후에 정본을 고쳐도 되돌아오지 않는다.")
    @GetMapping("/integrity")
    public ResponseEntity<TierIntegrityReport> integrity(
            @RequestParam(name = "sampleLimit", defaultValue = "" + DEFAULT_SAMPLE_LIMIT) int sampleLimit) {
        return ResponseEntity.ok(integrityUseCase.check(sampleLimit));
    }

    @Operation(summary = "적용 중인 등급 임계 확인 — 미리보기 결과를 해석하려면 기준을 알아야 한다")
    @GetMapping("/policy")
    public ResponseEntity<PolicyView> policy() {
        return ResponseEntity.ok(new PolicyView(policy.vipThreshold(), policy.strategicThreshold()));
    }

    public record PolicyView(BigDecimal vipThreshold, BigDecimal strategicThreshold) { }

    /** @param memo 변경 근거 — 필수. 근거 없는 등급 변경이 이력에 쌓이면 감사가 의미를 잃는다 */
    public record OverrideRequest(@NotNull SellerTierGrade tier, @NotBlank String memo) { }

    public record AssignmentView(Long sellerId, String tier, LocalDate effectiveFrom,
                                 LocalDate demotionGuardUntil) {
        static AssignmentView of(TierAssignment a) {
            return new AssignmentView(a.getSellerId(), a.getTier().name(),
                    a.getEffectiveFrom(), a.getDemotionGuardUntil());
        }
    }
}
