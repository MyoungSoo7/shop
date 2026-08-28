package github.lms.lemuel.sellertier.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase;
import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase.TierEvaluationReport;
import github.lms.lemuel.sellertier.application.port.in.CheckSellerTierIntegrityUseCase;
import github.lms.lemuel.sellertier.application.port.in.ListSellerTiersUseCase;
import github.lms.lemuel.sellertier.application.port.in.ListSellerTiersUseCase.SellerTierRoster;
import github.lms.lemuel.sellertier.application.port.in.ListSellerTiersUseCase.SellerTierRow;
import github.lms.lemuel.sellertier.application.port.in.OverrideSellerTierUseCase;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.SellerTierPolicy;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import github.lms.lemuel.sellertier.domain.TierCacheDrift;
import github.lms.lemuel.sellertier.domain.exception.SellerTierPolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 셀러 등급 운영 콘솔 (ADR 0031).
 *
 * <p>등급 하나가 수수료율·정산주기·홀드백을 동시에 바꾼다. 파라미터를 빠뜨린 호출이 전 셀러 등급을
 * 바꾸거나, 잘못된 요청이 500 으로 뭉개지면 안 된다 — 기본값과 상태코드를 여기서 못박는다.
 */
class AdminSellerTierControllerTest {

    private EvaluateSellerTiersUseCase evaluateUseCase;
    private OverrideSellerTierUseCase overrideUseCase;
    private CheckSellerTierIntegrityUseCase integrityUseCase;
    private ListSellerTiersUseCase listUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        evaluateUseCase = mock(EvaluateSellerTiersUseCase.class);
        overrideUseCase = mock(OverrideSellerTierUseCase.class);
        integrityUseCase = mock(CheckSellerTierIntegrityUseCase.class);
        listUseCase = mock(ListSellerTiersUseCase.class);
        SellerTierPolicy policy = SellerTierPolicy.of(
                new BigDecimal("500000000"), new BigDecimal("3000000000"));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminSellerTierController(
                        evaluateUseCase, overrideUseCase, integrityUseCase, listUseCase, policy))
                // LocalDate 를 담은 응답·에러가 직렬화되어야 상태코드 검증이 의미를 갖는다.
                // 날짜는 프로덕션(JacksonCompatConfig)과 같이 ISO-8601 문자열로 — 기본값인 숫자 배열로
                // 두면 여기서만 통과하고 실제 응답 형태를 검증하지 못한다.
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
                // 프로덕션과 같은 매핑을 본다 — BusinessException → 400 은 전역 advice 의 책임이다.
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private TierEvaluationReport emptyReport(boolean dryRun) {
        return new TierEvaluationReport(0, 0, 0, 0, 0, 0, dryRun, List.of());
    }

    @Test @DisplayName("evaluate: 파라미터 없이 호출하면 미리보기다 — 실수로 전 셀러가 바뀌지 않게")
    void evaluate_defaultsToDryRun() throws Exception {
        when(evaluateUseCase.evaluate(any(), anyBoolean(), anyInt())).thenReturn(emptyReport(true));

        mockMvc.perform(post("/admin/seller-tiers/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true));

        verify(evaluateUseCase).evaluate(eq(LocalDate.now()), eq(true), eq(1000));
    }

    @Test @DisplayName("evaluate: dryRun=false 를 명시해야 실제로 반영한다")
    void evaluate_appliesOnlyWhenExplicit() throws Exception {
        when(evaluateUseCase.evaluate(any(), anyBoolean(), anyInt())).thenReturn(emptyReport(false));

        mockMvc.perform(post("/admin/seller-tiers/evaluate")
                        .param("dryRun", "false").param("date", "2026-09-01").param("limit", "50"))
                .andExpect(status().isOk());

        verify(evaluateUseCase).evaluate(eq(LocalDate.of(2026, 9, 1)), eq(false), eq(50));
    }

    @Test @DisplayName("명부: 셀러별 등급·캐시·순매출을 낸다 — 이 콘솔에서 '누가 몇 등급인가'에 답하는 유일한 경로")
    void list_returnsRoster() throws Exception {
        when(listUseCase.list(any(), anyInt())).thenReturn(new SellerTierRoster(List.of(
                new SellerTierRow(13L, "vip@lemuel.co.kr", "김셀러", "VIP", "VIP",
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 11, 1), 0,
                        new BigDecimal("820000000"), 12, false),
                new SellerTierRow(21L, "new@lemuel.co.kr", null, null, "NORMAL",
                        null, null, 0, BigDecimal.ZERO, 1, false)), 2L, false));

        mockMvc.perform(get("/admin/seller-tiers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rows[0].sellerId").value(13))
                .andExpect(jsonPath("$.rows[0].email").value("vip@lemuel.co.kr"))
                .andExpect(jsonPath("$.rows[0].tier").value("VIP"))
                .andExpect(jsonPath("$.rows[0].netSales12m").value(820000000))
                // 날짜가 숫자 배열로 나가면 화면이 그대로 못 쓴다 — 프로덕션과 같은 ISO 문자열이어야 한다.
                .andExpect(jsonPath("$.rows[0].demotionGuardUntil").value("2026-11-01"))
                // 아직 산정되지 않은 셀러도 명부에 있어야 한다. 빠지면 관리자가 지정할 대상을 찾지 못한다.
                .andExpect(jsonPath("$.rows[1].tier").doesNotExist())
                .andExpect(jsonPath("$.rows[1].cachedTier").value("NORMAL"));

        verify(listUseCase).list(eq(LocalDate.now()), eq(200));
    }

    @Test @DisplayName("명부: 기준일·상한을 지정하면 그대로 전달한다")
    void list_passesDateAndLimit() throws Exception {
        when(listUseCase.list(any(), anyInt())).thenReturn(new SellerTierRoster(List.of(), 0L, false));

        mockMvc.perform(get("/admin/seller-tiers").param("date", "2026-09-01").param("limit", "10"))
                .andExpect(status().isOk());

        verify(listUseCase).list(eq(LocalDate.of(2026, 9, 1)), eq(10));
    }

    @Test @DisplayName("policy: 적용 중인 임계를 그대로 보여준다")
    void policy_exposesThresholds() throws Exception {
        mockMvc.perform(get("/admin/seller-tiers/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vipThreshold").value(500000000))
                .andExpect(jsonPath("$.strategicThreshold").value(3000000000L));
    }

    @Test @DisplayName("integrity: 표본 상한 기본값으로 검사하고 종류별 집계를 낸다")
    void integrity_defaultsSampleLimit() throws Exception {
        when(integrityUseCase.check(anyInt())).thenReturn(
                new CheckSellerTierIntegrityUseCase.TierIntegrityReport(
                        3L, java.util.Map.of("CACHE_STALE", 2, "CACHE_MISSING", 1),
                        List.of(TierCacheDrift.of(7L, "VIP", "NORMAL")), 0));

        mockMvc.perform(get("/admin/seller-tiers/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drifted").value(3))
                .andExpect(jsonPath("$.byKind.CACHE_STALE").value(2))
                .andExpect(jsonPath("$.samples[0].kind").value("CACHE_STALE"))
                .andExpect(jsonPath("$.samples[0].authoritativeTier").value("VIP"));

        verify(integrityUseCase).check(50);
    }

    @Test @DisplayName("integrity: 표본 상한을 지정하면 그대로 전달한다")
    void integrity_passesSampleLimit() throws Exception {
        when(integrityUseCase.check(anyInt())).thenReturn(
                new CheckSellerTierIntegrityUseCase.TierIntegrityReport(
                        0L, java.util.Map.of(), List.of(), 0));

        mockMvc.perform(get("/admin/seller-tiers/integrity").param("sampleLimit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drifted").value(0));

        verify(integrityUseCase).check(5);
    }

    @Test @DisplayName("override: 경로의 셀러와 본문의 등급·사유를 유스케이스에 넘긴다")
    void override_passesCommand() throws Exception {
        when(overrideUseCase.override(any(), any(), any(), any(), any())).thenReturn(
                TierAssignment.rehydrate(7L, SellerTierGrade.VIP,
                        LocalDate.of(2026, 8, 8), LocalDate.of(2026, 11, 8), 0));

        mockMvc.perform(post("/admin/seller-tiers/7/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tier":"VIP","memo":"전략 파트너 계약"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(7))
                .andExpect(jsonPath("$.tier").value("VIP"))
                .andExpect(jsonPath("$.demotionGuardUntil").value("2026-11-08"));

        verify(overrideUseCase).override(eq(7L), eq(SellerTierGrade.VIP), eq("전략 파트너 계약"),
                eq("admin"), eq(LocalDate.now()));
    }

    @Test @DisplayName("override: 사유가 없으면 400 — 요청 단계에서 막아 유스케이스까지 가지 않는다")
    void override_requiresMemo() throws Exception {
        mockMvc.perform(post("/admin/seller-tiers/7/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tier":"VIP"}
                                """))
                .andExpect(status().isBadRequest());

        verify(overrideUseCase, never()).override(any(), any(), any(), any(), any());
    }

    @Test @DisplayName("override: 등급이 없으면 400 — 무엇으로 바꿀지 미상인 채 지정되면 안 된다")
    void override_requiresTier() throws Exception {
        mockMvc.perform(post("/admin/seller-tiers/7/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memo":"협상 결과"}
                                """))
                .andExpect(status().isBadRequest());

        verify(overrideUseCase, never()).override(any(), any(), any(), any(), any());
    }

    @Test @DisplayName("override: 도메인 거부는 400 으로 나간다 — 500 으로 뭉개지지 않게")
    void override_domainRejectionIsBadRequest() throws Exception {
        when(overrideUseCase.override(any(), any(), any(), any(), any()))
                .thenThrow(new SellerTierPolicyException("등급 지정 사유(memo)는 필수입니다"));

        mockMvc.perform(post("/admin/seller-tiers/7/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tier":"VIP","memo":"  x  "}
                                """))
                .andExpect(status().isBadRequest());
    }
}
