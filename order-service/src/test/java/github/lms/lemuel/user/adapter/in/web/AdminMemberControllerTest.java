package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.user.application.port.in.ChangeUserRoleUseCase;
import github.lms.lemuel.user.application.port.in.ChangeUserRoleUseCase.RoleChangeResult;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberExport;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberPage;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberQuery;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberStatusCount;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberSummary;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원 콘솔의 HTTP 표면.
 *
 * <p>여기서 지키는 것 셋 — 화면 필터가 질의로 온전히 옮겨지는가, 모르는 enum 값이 조용히
 * 결과를 좁히지 않는가, 그리고 사유 없는 역할 변경이 400 으로 막히는가.
 */
@WebMvcTest(controllers = AdminMemberController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminMemberControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean SearchMembersUseCase searchMembersUseCase;
    @MockitoBean ChangeUserRoleUseCase changeUserRoleUseCase;
    @MockitoBean github.lms.lemuel.user.application.port.out.LoadUserPort loadUserPort;

    private static MemberSummary row() {
        return new MemberSummary(42L, "hong@lemuel.io", "홍길동", "010-1111-2222",
                "USER", "APPROVED", true,
                LocalDateTime.of(2026, 3, 1, 12, 0), LocalDateTime.of(2026, 3, 1, 12, 0));
    }

    @Test
    @DisplayName("GET /admin/members — 목록과 페이지 메타를 돌려준다")
    void search() throws Exception {
        when(searchMembersUseCase.search(any()))
                .thenReturn(new MemberPage(List.of(row()), 0, 50, 1, 1));

        mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("hong@lemuel.io"))
                .andExpect(jsonPath("$.content[0].membershipStatus").value("APPROVED"))
                .andExpect(jsonPath("$.content[0].active").value(true));
    }

    @Test
    @DisplayName("필터 파라미터는 그대로 질의가 된다")
    void passesFiltersThrough() throws Exception {
        when(searchMembersUseCase.search(any())).thenReturn(new MemberPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/members")
                        .param("keyword", "홍")
                        .param("role", "MANAGER")
                        .param("status", "SUSPENDED")
                        .param("active", "false")
                        .param("joinedFrom", "2026-01-01")
                        .param("joinedTo", "2026-03-31")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk());

        ArgumentCaptor<MemberQuery> captor = ArgumentCaptor.forClass(MemberQuery.class);
        verify(searchMembersUseCase).search(captor.capture());
        MemberQuery query = captor.getValue();
        assertThat(query.keyword()).isEqualTo("홍");
        assertThat(query.role()).isEqualTo(UserRole.MANAGER);
        assertThat(query.status()).isEqualTo(MembershipStatus.SUSPENDED);
        assertThat(query.active()).isFalse();
        assertThat(query.joinedFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(query.joinedTo()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("모르는 역할 이름은 USER 로 떨어지지 않고 조건에서 빠진다 — 오타가 결과를 조용히 좁히면 안 된다")
    void unknownRoleDropsFilterInsteadOfDefaulting() throws Exception {
        when(searchMembersUseCase.search(any())).thenReturn(new MemberPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/members").param("role", "MANGER"))
                .andExpect(status().isOk());

        ArgumentCaptor<MemberQuery> captor = ArgumentCaptor.forClass(MemberQuery.class);
        verify(searchMembersUseCase).search(captor.capture());
        assertThat(captor.getValue().role()).isNull();
    }

    @Test
    @DisplayName("상태별 집계에는 상태 필터를 걸지 않는다 — 걸면 고른 상태 하나만 남아 집계가 무의미해진다")
    void statusCountsIgnoreStatusFilter() throws Exception {
        when(searchMembersUseCase.countByStatus(any()))
                .thenReturn(List.of(new MemberStatusCount("PENDING", 4)));

        mockMvc.perform(get("/admin/members/status-counts").param("keyword", "홍"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].membershipStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].count").value(4));

        ArgumentCaptor<MemberQuery> captor = ArgumentCaptor.forClass(MemberQuery.class);
        verify(searchMembersUseCase).countByStatus(captor.capture());
        assertThat(captor.getValue().status()).isNull();
        assertThat(captor.getValue().keyword()).isEqualTo("홍");
    }

    @Test
    @DisplayName("GET /admin/members/enums — 필터 목록은 서버 enum 이 정본이다")
    void enumsAreServerOwned() throws Exception {
        mockMvc.perform(get("/admin/members/enums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.hasItem("MANAGER")))
                .andExpect(jsonPath("$.membershipStatuses").value(org.hamcrest.Matchers.hasItem("SUSPENDED")));
    }

    @Test
    @DisplayName("CSV 는 BOM 으로 시작하고 잘림 여부를 헤더로 알린다")
    void exportsCsv() throws Exception {
        when(searchMembersUseCase.export(any()))
                .thenReturn(new MemberExport(List.of(row()), true, 12_345));

        MvcResult result = mockMvc.perform(get("/admin/members/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "true"))
                .andExpect(header().string("X-Export-Total", "12345"))
                .andReturn();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        assertThat(body).contains("\"hong@lemuel.io\"").contains("\"홍길동\"");
        // 연락처가 '-' 로 시작하지는 않지만, 수식 주입 차단 규약이 살아 있는지 함께 본다.
        assertThat(body).contains("\"010-1111-2222\"");
    }

    @Test
    @DisplayName("PATCH /admin/members/{id}/role — 역할을 바꾸고 결과 회원을 돌려준다")
    void changesRole() throws Exception {
        User changed = User.rehydrate(42L, "hong@lemuel.io", "hash", UserRole.MANAGER, "홍길동",
                "010-1111-2222", true, MembershipStatus.APPROVED,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 0, 0));
        when(changeUserRoleUseCase.changeRole(anyLong(), any(), any(), any()))
                .thenReturn(new RoleChangeResult(changed, UserRole.USER));

        mockMvc.perform(patch("/admin/members/42/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MANAGER\",\"reason\":\"CS 팀 배치\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));

        verify(changeUserRoleUseCase).changeRole(eq(42L), eq(UserRole.MANAGER), eq("CS 팀 배치"), any());
    }

    @Test
    @DisplayName("조작자는 JWT 주체(이메일)로 조회해 넘긴다 — 숫자 파싱하면 자기 자신 차단이 조용히 무력화된다")
    void resolvesActorByEmailSubject() throws Exception {
        User actor = User.rehydrate(7L, "ops@lemuel.io", "hash", UserRole.ADMIN, "운영자",
                null, true, MembershipStatus.APPROVED,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0));
        when(loadUserPort.findByEmail("ops@lemuel.io")).thenReturn(java.util.Optional.of(actor));
        when(changeUserRoleUseCase.changeRole(anyLong(), any(), any(), any()))
                .thenReturn(new RoleChangeResult(actor, UserRole.USER));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops@lemuel.io", null, List.of()));
        try {
            mockMvc.perform(patch("/admin/members/42/role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MANAGER\",\"reason\":\"CS 팀 배치\"}"))
                    .andExpect(status().isOk());
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(changeUserRoleUseCase).changeRole(eq(42L), eq(UserRole.MANAGER), eq("CS 팀 배치"), eq(7L));
    }

    @Test
    @DisplayName("사유 없는 역할 변경은 400 이다 — 유스케이스까지 가지 않는다")
    void rejectsRoleChangeWithoutReason() throws Exception {
        mockMvc.perform(patch("/admin/members/42/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MANAGER\",\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
