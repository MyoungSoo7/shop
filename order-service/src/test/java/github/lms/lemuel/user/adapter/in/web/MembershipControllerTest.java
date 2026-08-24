package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.user.application.port.in.ApproveMembershipUseCase;
import github.lms.lemuel.user.application.port.in.GetPendingMembersUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MembershipController.class)
@AutoConfigureMockMvc(addFilters = false)
class MembershipControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ApproveMembershipUseCase approveMembershipUseCase;
    @MockitoBean GetPendingMembersUseCase getPendingMembersUseCase;
    @MockitoBean LoadUserPort loadUserPort;

    private User member(long id, UserRole role) {
        User u = User.createWithProfile("m" + id + "@b.com", "hash", role, "회원" + id, "010-0000-0000");
        u.assignId(id);
        return u;
    }

    @BeforeEach
    void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@b.com", "n/a", List.of()));
        when(loadUserPort.findByEmail("admin@b.com"))
                .thenReturn(Optional.of(member(99L, UserRole.ADMIN)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /memberships/pending: 대기 회원 목록")
    void pending() throws Exception {
        User pending = member(1L, UserRole.COMPANY);
        pending.markPending();
        when(getPendingMembersUseCase.getPendingMembers()).thenReturn(List.of(pending));

        mockMvc.perform(get("/memberships/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].membershipStatus").value("PENDING"));
    }

    @Test
    @DisplayName("POST /memberships/{id}/approve: 승인 위임")
    void approve() throws Exception {
        when(approveMembershipUseCase.approve(1L, 99L)).thenReturn(member(1L, UserRole.COMPANY));

        mockMvc.perform(post("/memberships/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
        verify(approveMembershipUseCase).approve(1L, 99L);
    }

    @Test
    @DisplayName("POST /memberships/{id}/reject: 사유와 함께 반려 위임")
    void reject() throws Exception {
        when(approveMembershipUseCase.reject(eq(1L), eq("서류 미비"), eq(99L)))
                .thenReturn(member(1L, UserRole.COMPANY));

        mockMvc.perform(post("/memberships/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"서류 미비"}
                                """))
                .andExpect(status().isOk());
        verify(approveMembershipUseCase).reject(1L, "서류 미비", 99L);
    }

    @Test
    @DisplayName("POST /memberships/{id}/suspend: 본문 없이 정지 위임 (reason null)")
    void suspend() throws Exception {
        when(approveMembershipUseCase.suspend(eq(1L), isNull(), eq(99L)))
                .thenReturn(member(1L, UserRole.COMPANY));

        mockMvc.perform(post("/memberships/1/suspend"))
                .andExpect(status().isOk());
        verify(approveMembershipUseCase).suspend(1L, null, 99L);
    }

    @Test
    @DisplayName("POST /memberships/{id}/reinstate: 정지 해제 위임")
    void reinstate() throws Exception {
        when(approveMembershipUseCase.reinstate(1L, 99L)).thenReturn(member(1L, UserRole.COMPANY));

        mockMvc.perform(post("/memberships/1/reinstate"))
                .andExpect(status().isOk());
        verify(approveMembershipUseCase).reinstate(1L, 99L);
    }
}
