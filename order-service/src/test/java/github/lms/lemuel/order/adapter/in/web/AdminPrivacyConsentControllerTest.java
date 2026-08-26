package github.lms.lemuel.order.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.order.application.port.in.GetPrivacyConsentUseCase;
import github.lms.lemuel.order.application.port.in.GetPrivacyConsentUseCase.ConsentView;
import github.lms.lemuel.order.domain.ConsentType;
import github.lms.lemuel.order.domain.OrderPrivacyConsent;
import github.lms.lemuel.order.domain.PrivacyConsentTerms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동의 이력 운영 콘솔.
 *
 * <p>두 축이 따로 있는 것이 핵심이다. <b>사람으로</b> 찾는 축은 정보주체의 열람 요구에 답하고,
 * <b>문안 버전으로</b> 찾는 축은 문안을 고친 뒤 "옛 버전으로 동의한 사람이 아직 남아 있는가"를
 * 센다. 한 축으로 합치면 뒤엣것을 할 수 없다.
 *
 * <p>운영자 응답에만 접속지(IP)가 있다 — 고객 응답과 다른 점이고, 그 차이가 유지되는지를 본다.
 */
class AdminPrivacyConsentControllerTest {

    private static final LocalDateTime AGREED_AT = LocalDateTime.of(2026, 8, 27, 10, 0);

    private GetPrivacyConsentUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(GetPrivacyConsentUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminPrivacyConsentController(useCase))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    private static ConsentView view(boolean bodyUnchanged) {
        PrivacyConsentTerms terms = PrivacyConsentTerms.restore(1L, "THIRD_PARTY_DELIVERY", 2,
                ConsentType.THIRD_PARTY_PROVISION, "배송을 위한 개인정보 제3자 제공 동의", "배송업체",
                "주문 상품의 배송", "받는 분 이름, 휴대전화번호, 주소", "배송 완료 후 90일",
                "전문입니다", "hash", true, AGREED_AT.minusDays(30), null, AGREED_AT.minusDays(30));
        OrderPrivacyConsent consent = terms.accept(7L, 42L, true, AGREED_AT, "203.0.113.7");
        return new ConsentView(consent, bodyUnchanged);
    }

    @Test @DisplayName("사용자 축: 동의 당시 고지 내용과 접속지까지 함께 돌려준다")
    void byUser() throws Exception {
        when(useCase.ofUser(anyLong(), anyInt())).thenReturn(List.of(view(true)));

        mockMvc.perform(get("/admin/privacy-consents").param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(7))
                .andExpect(jsonPath("$[0].userId").value(42))
                .andExpect(jsonPath("$[0].termsCode").value("THIRD_PARTY_DELIVERY"))
                .andExpect(jsonPath("$[0].termsVersion").value(2))
                .andExpect(jsonPath("$[0].consentType").value("THIRD_PARTY_PROVISION"))
                .andExpect(jsonPath("$[0].agreed").value(true))
                .andExpect(jsonPath("$[0].recipient").value("배송업체"))
                .andExpect(jsonPath("$[0].providedItems").value("받는 분 이름, 휴대전화번호, 주소"))
                .andExpect(jsonPath("$[0].retention").value("배송 완료 후 90일"))
                // 다투는 국면에서는 "언제"만으로 모자란다. 고객 응답에는 없는 칸이다.
                .andExpect(jsonPath("$[0].ipAddress").value("203.0.113.7"))
                .andExpect(jsonPath("$[0].bodyUnchanged").value(true));
    }

    @Test @DisplayName("문안 축: 코드와 버전을 그대로 넘긴다")
    void byTermsVersion() throws Exception {
        when(useCase.ofTermsVersion(anyString(), anyInt(), anyInt())).thenReturn(List.of(view(true)));

        mockMvc.perform(get("/admin/privacy-consents")
                        .param("termsCode", "THIRD_PARTY_DELIVERY")
                        .param("termsVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verify(useCase).ofTermsVersion("THIRD_PARTY_DELIVERY", 2, 100);
    }

    @Test @DisplayName("limit 을 안 주면 100 건")
    void defaultLimit() throws Exception {
        when(useCase.ofUser(anyLong(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/privacy-consents").param("userId", "42"))
                .andExpect(status().isOk());

        verify(useCase).ofUser(42L, 100);
    }

    @Test @DisplayName("limit 을 주면 그 값이 그대로 간다 — 상한은 서비스가 건다")
    void explicitLimit() throws Exception {
        when(useCase.ofUser(anyLong(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/privacy-consents").param("userId", "42").param("limit", "9999"))
                .andExpect(status().isOk());

        // 컨트롤러가 자르지 않는 것이 의도다. 상한이 두 군데 있으면 둘이 어긋난다.
        verify(useCase).ofUser(42L, 9999);
    }

    @Test @DisplayName("문안이 손질됐으면 bodyUnchanged=false 를 그대로 내보낸다")
    void surfacesTamperedBody() throws Exception {
        when(useCase.ofUser(anyLong(), anyInt())).thenReturn(List.of(view(false)));

        // 화면에서 지우지 않는다 — 버전을 올리지 않고 문장을 고친 것 자체가 조사 대상이다.
        mockMvc.perform(get("/admin/privacy-consents").param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bodyUnchanged").value(false));
    }

    @Test @DisplayName("조회 결과가 없으면 빈 목록")
    void emptyResult() throws Exception {
        when(useCase.ofUser(anyLong(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/privacy-consents").param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
