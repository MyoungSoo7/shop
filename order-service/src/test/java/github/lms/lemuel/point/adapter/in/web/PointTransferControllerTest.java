package github.lms.lemuel.point.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.point.application.port.in.TransferPointUseCase;
import github.lms.lemuel.point.application.port.in.TransferPointUseCase.PointTransferHistoryEntry;
import github.lms.lemuel.point.application.port.in.TransferPointUseCase.TransferPointCommand;
import github.lms.lemuel.point.application.port.in.TransferPointUseCase.TransferPointResult;
import github.lms.lemuel.point.domain.exception.PointTransferRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PointTransferController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("포인트 선물 컨트롤러")
class PointTransferControllerTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-08-28T10:00:00+09:00");

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean TransferPointUseCase transferPointUseCase;

    private static void login(long uid) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(uid, uid + "@x.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static String body(String amount, String message) {
        return """
                {"requestId":"req-1","recipientEmail":"friend@example.com",
                 "recipientName":"김받는","amount":%s,"message":%s}
                """.formatted(amount, message == null ? "null" : "\"" + message + "\"");
    }

    @Test
    @DisplayName("보내는 이는 본문이 아니라 토큰에서 온다 — 요청이 주체를 지정할 수 없다")
    void derivesSenderFromToken() throws Exception {
        login(42L);
        when(transferPointUseCase.transfer(any())).thenReturn(new TransferPointResult(
                "PT20260828-00000001", "fr****@example.com", "김받는",
                new BigDecimal("1000"), new BigDecimal("4000"), T0, false));

        mockMvc.perform(post("/api/points/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000", "고마워")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferNo").value("PT20260828-00000001"))
                .andExpect(jsonPath("$.recipientEmail").value("fr****@example.com"))
                .andExpect(jsonPath("$.remainingBalance").value(4000))
                .andExpect(jsonPath("$.alreadyProcessed").value(false));

        org.mockito.ArgumentCaptor<TransferPointCommand> captor =
                org.mockito.ArgumentCaptor.forClass(TransferPointCommand.class);
        verify(transferPointUseCase).transfer(captor.capture());
        assertThat(captor.getValue().senderUserId()).isEqualTo(42L);
        assertThat(captor.getValue().recipientEmail()).isEqualTo("friend@example.com");
    }

    @Test
    @DisplayName("받는 이를 못 찾으면 400 이고 사유는 하나뿐이다")
    void unknownRecipientIsBadRequest() throws Exception {
        login(42L);
        when(transferPointUseCase.transfer(any()))
                .thenThrow(PointTransferRejectedException.recipientUnknown());

        mockMvc.perform(post("/api/points/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("자기 자신에게 보내면 400")
    void selfTransferIsBadRequest() throws Exception {
        login(42L);
        when(transferPointUseCase.transfer(any())).thenThrow(PointTransferRejectedException.self());

        mockMvc.perform(post("/api/points/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이메일 형식이 아니면 유스케이스까지 가지 않는다")
    void rejectsMalformedEmail() throws Exception {
        login(42L);

        mockMvc.perform(post("/api/points/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-1","recipientEmail":"not-an-email",
                                 "recipientName":"김받는","amount":1000}
                                """))
                .andExpect(status().isBadRequest());

        verify(transferPointUseCase, never()).transfer(any());
    }

    @Test
    @DisplayName("요청 식별자가 없으면 유스케이스까지 가지 않는다 — 멱등 키 없는 송금은 받지 않는다")
    void rejectsMissingRequestId() throws Exception {
        login(42L);

        mockMvc.perform(post("/api/points/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientEmail":"friend@example.com",
                                 "recipientName":"김받는","amount":1000}
                                """))
                .andExpect(status().isBadRequest());

        verify(transferPointUseCase, never()).transfer(any());
    }

    @Test
    @DisplayName("이력은 방향과 함께 최신순으로 내려간다")
    void listsHistory() throws Exception {
        login(42L);
        when(transferPointUseCase.history(anyLong(), anyInt())).thenReturn(List.of(
                new PointTransferHistoryEntry("PT-1", true, "김받는",
                        new BigDecimal("1000"), "고마워", T0),
                new PointTransferHistoryEntry("PT-2", false, "이보낸",
                        new BigDecimal("500"), null, T0)));

        mockMvc.perform(get("/api/points/transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outgoing").value(true))
                .andExpect(jsonPath("$[0].counterpartName").value("김받는"))
                .andExpect(jsonPath("$[1].outgoing").value(false))
                .andExpect(jsonPath("$[1].message").isEmpty());

        verify(transferPointUseCase).history(42L, 20);
    }
}
