package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.application.port.in.GetPrivacyConsentUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 동의 이력 운영 콘솔 — <b>읽기 전용</b>이다.
 *
 * <p>고치는 경로를 두지 않은 것이 의도다. 동의 이력을 운영자가 수정할 수 있으면 그 이력은
 * 더 이상 증거가 아니다 — "고칠 수 있었다"는 사실만으로 근거로서의 값이 떨어진다.
 *
 * <p>권한은 {@code SecurityConfig} 의 {@code /admin/privacy-consents/**} 매처가 정한다. 이 저장소의
 * {@code @PreAuthorize} 는 {@code @EnableMethodSecurity} 가 없어 조용히 무시되므로, 어노테이션을
 * 붙여 두면 지켜지는 것처럼 보이기만 하고 실제로는 아무것도 막지 않는다.
 */
@Tag(name = "Admin", description = "주문 시점 개인정보 동의 이력 조회(운영자)")
@Validated
@RestController
@RequestMapping("/admin/privacy-consents")
@RequiredArgsConstructor
public class AdminPrivacyConsentController {

    private static final int DEFAULT_LIMIT = 100;

    private final GetPrivacyConsentUseCase consentService;

    @Operation(summary = "사용자 동의 이력 조회",
            description = "한 사람이 언제 무엇에 동의했는지 최근 순으로. 정보주체의 열람 요구에 답하는 경로다.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping(params = "userId")
    public ResponseEntity<List<AdminConsentResponse>> ofUser(
            @RequestParam @Positive Long userId,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok(consentService.ofUser(userId, limit).stream()
                .map(AdminConsentResponse::from)
                .toList());
    }

    @Operation(summary = "문안 버전별 동의 이력 조회",
            description = "특정 (문안 코드, 버전) 으로 동의한 이력. 문안을 고친 뒤 "
                    + "\"옛 버전으로 동의한 사람이 남아 있는가\" 를 확인할 때 쓴다 — "
                    + "0 이 아니면 재동의를 받아야 한다는 뜻이다.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping(params = {"termsCode", "termsVersion"})
    public ResponseEntity<List<AdminConsentResponse>> ofTermsVersion(
            @RequestParam String termsCode,
            @RequestParam @Positive int termsVersion,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok(consentService.ofTermsVersion(termsCode, termsVersion, limit).stream()
                .map(AdminConsentResponse::from)
                .toList());
    }

    /**
     * 운영자 응답 — 고객 응답과 달리 {@code ipAddress} 를 포함한다.
     *
     * <p>동의를 다투게 되면 "언제 어디서" 가 함께 필요하기 때문이다. 다만 프록시 뒤에서 관찰한
     * 값이라 정확하지 않을 수 있어 보조 증거로만 쓴다 — 이것만으로 사람을 특정하지 않는다.
     */
    public record AdminConsentResponse(Long orderId,
                                       Long userId,
                                       String termsCode,
                                       int termsVersion,
                                       String consentType,
                                       boolean agreed,
                                       String recipient,
                                       String purpose,
                                       String providedItems,
                                       String retention,
                                       LocalDateTime agreedAt,
                                       String ipAddress,
                                       boolean bodyUnchanged) {

        static AdminConsentResponse from(GetPrivacyConsentUseCase.ConsentView view) {
            var consent = view.consent();
            return new AdminConsentResponse(consent.getOrderId(), consent.getUserId(),
                    consent.getTermsCode(), consent.getTermsVersion(), consent.getConsentType().name(),
                    consent.isAgreed(), consent.getRecipient(), consent.getPurpose(),
                    consent.getProvidedItems(), consent.getRetention(), consent.getAgreedAt(),
                    consent.getIpAddress(), view.bodyUnchanged());
        }
    }
}
