package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.application.port.in.GetPrivacyConsentUseCase;
import github.lms.lemuel.order.domain.PrivacyConsentTerms;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 동의 문안 조회와 주문별 동의 이력 조회.
 *
 * <p>두 경로 모두 {@code SecurityConfig} 의 {@code anyRequest().authenticated()} 로 떨어진다 —
 * 문안을 공개로 열 이유는 있지만(동의하기 전에 읽을 수 있어야 한다) 결제 자체가 로그인 경로라
 * 실익이 없고, 매처를 늘리면 그만큼 열어 둔 문이 늘어난다. 이력 조회는 인증 위에 한 겹 더 —
 * 저장된 행의 주인과 대조한다.
 */
@Tag(name = "Order", description = "주문 시점 개인정보 동의 문안·이력")
@Validated
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class PrivacyConsentController {

    private final GetPrivacyConsentUseCase getPrivacyConsentUseCase;

    @Operation(summary = "동의 문안 조회",
            description = "결제 화면이 보여 줘야 할 현행 동의 문안. 필수 항목이 앞에 온다. "
                    + "응답의 termsCode·termsVersion 을 주문 생성 요청에 그대로 실어 보내야 한다 — "
                    + "그 사이 문안이 바뀌었으면 주문이 409 로 거절되고 화면을 다시 받아야 한다.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping("/consent-terms")
    public ResponseEntity<List<TermsResponse>> currentTerms() {
        return ResponseEntity.ok(getPrivacyConsentUseCase.currentTerms().stream()
                .map(TermsResponse::from)
                .toList());
    }

    @Operation(summary = "주문 동의 이력 조회",
            description = "이 주문에서 무엇에 동의했는지. 거절한 선택 항목도 함께 나온다 — "
                    + "\"물었고 거절했다\" 도 기록이다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "본인 주문이 아님")
    })
    @GetMapping("/{orderId}/privacy-consents")
    public ResponseEntity<List<ConsentResponse>> ofOrder(@PathVariable @Positive Long orderId) {
        List<GetPrivacyConsentUseCase.ConsentView> views = getPrivacyConsentUseCase.ofOrder(orderId);
        if (views.isEmpty()) {
            // 없는 주문과 남의 주문을 같은 응답으로 돌려준다. 빈 목록이라 새어 나갈 내용도 없다.
            return ResponseEntity.ok(List.of());
        }
        // 이력의 주인은 경로가 아니라 저장된 행이 말한다. 요청 파라미터로 소유자를 정하면
        // 주문 번호만 바꿔 남의 동의 이력(이름·전화번호가 아니라 무엇에 동의했는지)이 열린다.
        ResourceOwnership.requireSelfOrAdmin(views.getFirst().consent().getUserId());
        return ResponseEntity.ok(views.stream().map(ConsentResponse::from).toList());
    }

    /**
     * 문안 응답.
     *
     * @param body 전문. 화면에 접었다 펴는 그 문장이다 — 목록에서 빼면 클라이언트가 전문을 따로
     *             한 번 더 불러야 하고, 그 호출을 빠뜨린 화면은 요약만 보여 준 채 동의를 받게 된다
     */
    public record TermsResponse(String code,
                                int version,
                                String consentType,
                                String title,
                                String recipient,
                                String purpose,
                                String providedItems,
                                String retention,
                                String body,
                                boolean required,
                                LocalDateTime effectiveFrom) {

        static TermsResponse from(PrivacyConsentTerms terms) {
            return new TermsResponse(terms.getCode(), terms.getVersion(), terms.getConsentType().name(),
                    terms.getTitle(), terms.getRecipient(), terms.getPurpose(), terms.getProvidedItems(),
                    terms.getRetention(), terms.getBody(), terms.isRequired(), terms.getEffectiveFrom());
        }
    }

    /**
     * 이력 응답.
     *
     * <p>{@code ipAddress} 는 내보내지 않는다. 본인에게 보여 줄 값이 아니고(자기 IP 를 확인하러 오는
     * 화면이 아니다), 이력 목록이 유출됐을 때 접속지까지 함께 나가면 피해가 커진다. 감사에 필요하면
     * 관리자 경로에서 본다.
     *
     * @param bodyUnchanged 동의 당시 문안이 지금도 같은가. {@code false} 면 버전을 올리지 않고
     *                      문장을 고친 것이라 그 자체가 조사 대상이다
     */
    public record ConsentResponse(String termsCode,
                                  int termsVersion,
                                  String consentType,
                                  boolean agreed,
                                  String recipient,
                                  String purpose,
                                  String providedItems,
                                  String retention,
                                  LocalDateTime agreedAt,
                                  boolean bodyUnchanged) {

        static ConsentResponse from(GetPrivacyConsentUseCase.ConsentView view) {
            var consent = view.consent();
            return new ConsentResponse(consent.getTermsCode(), consent.getTermsVersion(),
                    consent.getConsentType().name(), consent.isAgreed(), consent.getRecipient(),
                    consent.getPurpose(), consent.getProvidedItems(), consent.getRetention(),
                    consent.getAgreedAt(), view.bodyUnchanged());
        }
    }
}
