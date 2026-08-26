package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.order.application.port.in.GetPrivacyConsentUseCase;
import github.lms.lemuel.order.application.port.in.GetPrivacyConsentUseCase.ConsentView;
import github.lms.lemuel.order.domain.ConsentType;
import github.lms.lemuel.order.domain.OrderPrivacyConsent;
import github.lms.lemuel.order.domain.PrivacyConsentTerms;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고객이 보는 동의 문안·이력 경로.
 *
 * <p>여기서 지키는 것은 두 가지다. <b>이력의 주인은 경로가 아니라 저장된 행이 말한다</b>는 것과,
 * <b>고객 응답에는 접속지(IP)를 싣지 않는다</b>는 것이다. 앞엣것이 무너지면 주문 번호를 하나씩
 * 바꿔 가며 남이 무엇에 동의했는지 읽을 수 있고, 뒤엣것이 무너지면 이력 한 건이 새어 나갈 때
 * 접속지까지 함께 나간다.
 */
@WebMvcTest(controllers = PrivacyConsentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PrivacyConsentControllerTest {

    private static final LocalDateTime AGREED_AT = LocalDateTime.of(2026, 8, 27, 10, 0);

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GetPrivacyConsentUseCase getPrivacyConsentUseCase;

    /** addFilters=false 슬라이스라 JWT 주체를 SecurityContext 에 직접 세팅한다(OrderControllerTest 와 동일). */
    private static void login(long uid, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(uid, uid + "@x.com", role),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static PrivacyConsentTerms terms() {
        return PrivacyConsentTerms.restore(1L, "THIRD_PARTY_DELIVERY", 2,
                ConsentType.THIRD_PARTY_PROVISION, "배송을 위한 개인정보 제3자 제공 동의", "배송업체",
                "주문 상품의 배송", "받는 분 이름, 휴대전화번호, 주소", "배송 완료 후 90일",
                "전문입니다", "hash", true, AGREED_AT.minusDays(30), null, AGREED_AT.minusDays(30));
    }

    private static ConsentView view(long userId) {
        OrderPrivacyConsent consent = terms().accept(7L, userId, true, AGREED_AT, "203.0.113.7");
        return new ConsentView(consent, true);
    }

    @Test
    @DisplayName("문안 조회: 전문까지 함께 내려간다 — 요약만 보고 동의를 받는 화면이 생기지 않도록")
    void currentTerms_includesBody() throws Exception {
        when(getPrivacyConsentUseCase.currentTerms()).thenReturn(List.of(terms()));

        mockMvc.perform(get("/orders/consent-terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("THIRD_PARTY_DELIVERY"))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].consentType").value("THIRD_PARTY_PROVISION"))
                .andExpect(jsonPath("$[0].recipient").value("배송업체"))
                .andExpect(jsonPath("$[0].body").value("전문입니다"))
                .andExpect(jsonPath("$[0].required").value(true));
    }

    @Test
    @DisplayName("이력 조회: 본인 주문은 열린다. 응답에 접속지는 없다")
    void ofOrder_ownerSeesHistoryWithoutIp() throws Exception {
        when(getPrivacyConsentUseCase.ofOrder(7L)).thenReturn(List.of(view(42L)));
        login(42L, "USER");

        mockMvc.perform(get("/orders/7/privacy-consents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].termsCode").value("THIRD_PARTY_DELIVERY"))
                .andExpect(jsonPath("$[0].agreed").value(true))
                .andExpect(jsonPath("$[0].bodyUnchanged").value(true))
                // 고객 응답에는 IP 칸 자체가 없다. 있으면 값이 비어 있어도 언젠가 채워진다.
                .andExpect(jsonPath("$[0].ipAddress").doesNotExist());
    }

    @Test
    @DisplayName("이력 조회: 남의 주문이면 403 — 주인은 경로가 아니라 저장된 행이 말한다")
    void ofOrder_foreignOrderIsForbidden() throws Exception {
        when(getPrivacyConsentUseCase.ofOrder(7L)).thenReturn(List.of(view(42L)));
        login(43L, "USER");

        mockMvc.perform(get("/orders/7/privacy-consents"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이력 조회: 운영자는 남의 주문도 본다 — 민원 대응 경로다")
    void ofOrder_adminBypassesOwnership() throws Exception {
        when(getPrivacyConsentUseCase.ofOrder(7L)).thenReturn(List.of(view(42L)));
        login(1L, "ADMIN");

        mockMvc.perform(get("/orders/7/privacy-consents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].termsCode").value("THIRD_PARTY_DELIVERY"));
    }

    @Test
    @DisplayName("이력 조회: 없는 주문과 남의 빈 주문이 같은 응답이다 — 존재 여부가 새지 않는다")
    void ofOrder_emptyIsIndistinguishable() throws Exception {
        when(getPrivacyConsentUseCase.ofOrder(anyLong())).thenReturn(List.of());
        login(43L, "USER");

        // 소유권 검사에 닿기 전에 빈 목록으로 끝난다. 여기서 403 을 주면 "그 주문은 있는데
        // 네 것이 아니다" 가 되어 주문 번호의 존재 여부를 훑을 수 있게 된다.
        mockMvc.perform(get("/orders/999/privacy-consents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("이력 조회: 주문 번호가 0 이하면 400")
    void ofOrder_rejectsNonPositiveOrderId() throws Exception {
        login(42L, "USER");

        mockMvc.perform(get("/orders/0/privacy-consents"))
                .andExpect(status().isBadRequest());
    }
}
