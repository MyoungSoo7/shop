package github.lms.lemuel.auditconsole.adapter.in.web;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogExport;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogPage;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogRow;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.common.audit.domain.AuditAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 감사 로그 콘솔의 HTTP 표면.
 *
 * <p>여기서 지키는 것은 <b>화면 파라미터가 유스케이스 질의로 온전히 옮겨지는가</b>와
 * <b>잘린 내보내기를 사용자가 알 수 있는가</b> 둘이다. 조회 로직 자체는 서비스 단위 테스트가 지킨다.
 */
@WebMvcTest(controllers = AdminAuditLogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminAuditLogControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean SearchAuditLogsUseCase searchAuditLogsUseCase;

    private static AuditLogRow row() {
        return new AuditLogRow(11L, 7L, "admin@lemuel.io", "USER_ROLE_CHANGED", "USER", "42",
                "{\"before\":\"USER\",\"after\":\"MANAGER\"}", "10.0.0.1", "curl",
                LocalDateTime.of(2026, 3, 2, 9, 30));
    }

    @Test
    @DisplayName("GET /admin/audit-logs — 목록과 페이지 메타를 돌려준다")
    void search() throws Exception {
        when(searchAuditLogsUseCase.search(any()))
                .thenReturn(new AuditLogPage(List.of(row()), 0, 50, 1, 1));

        mockMvc.perform(get("/admin/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].actorEmail").value("admin@lemuel.io"))
                .andExpect(jsonPath("$.content[0].action").value("USER_ROLE_CHANGED"))
                .andExpect(jsonPath("$.content[0].detailJson").value("{\"before\":\"USER\",\"after\":\"MANAGER\"}"));
    }

    @Test
    @DisplayName("필터 파라미터는 그대로 유스케이스 질의가 된다")
    void passesFiltersThrough() throws Exception {
        when(searchAuditLogsUseCase.search(any()))
                .thenReturn(new AuditLogPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/audit-logs")
                        .param("actorEmail", "ops@lemuel.io")
                        .param("actorId", "7")
                        .param("action", "PAYOUT_EXECUTED")
                        .param("resourceType", "PAYOUT")
                        .param("resourceId", "P-1")
                        .param("from", "2026-03-01")
                        .param("to", "2026-03-31")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogQuery> captor = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(searchAuditLogsUseCase).search(captor.capture());
        AuditLogQuery query = captor.getValue();
        assertThat(query.actorEmail()).isEqualTo("ops@lemuel.io");
        assertThat(query.actorId()).isEqualTo(7L);
        assertThat(query.action()).isEqualTo(AuditAction.PAYOUT_EXECUTED);
        assertThat(query.resourceType()).isEqualTo("PAYOUT");
        assertThat(query.resourceId()).isEqualTo("P-1");
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(query.to()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("모르는 액션 이름은 400 이 아니라 '필터 미적용'으로 흘린다 — 필터 하나로 이력 전체가 막히면 안 된다")
    void unknownActionFallsBackToNoFilter() throws Exception {
        when(searchAuditLogsUseCase.search(any()))
                .thenReturn(new AuditLogPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/audit-logs").param("action", "SOMETHING_REMOVED_LAST_YEAR"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogQuery> captor = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(searchAuditLogsUseCase).search(captor.capture());
        assertThat(captor.getValue().action()).isNull();
    }

    @Test
    @DisplayName("액션 이름은 대소문자를 가리지 않는다")
    void actionIsCaseInsensitive() throws Exception {
        when(searchAuditLogsUseCase.search(any()))
                .thenReturn(new AuditLogPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/audit-logs").param("action", "login_failed"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogQuery> captor = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(searchAuditLogsUseCase).search(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.LOGIN_FAILED);
    }

    @Test
    @DisplayName("GET /admin/audit-logs/actions — 서버 enum 이 필터 목록의 정본이다")
    void actionsAreServerOwned() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/audit-logs/actions"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("PAYOUT_EXECUTED").contains("USER_ROLE_CHANGED");
        // 정렬돼 있어야 드롭다운이 안정적이다.
        assertThat(body.indexOf("LOGIN_FAILED")).isLessThan(body.indexOf("PAYOUT_EXECUTED"));
    }

    @Test
    @DisplayName("GET /admin/audit-logs/action-counts — 액션별 건수를 돌려준다")
    void actionCounts() throws Exception {
        when(searchAuditLogsUseCase.countByAction(any()))
                .thenReturn(List.of(new AuditActionCount("LOGIN_FAILED", 12)));

        mockMvc.perform(get("/admin/audit-logs/action-counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$[0].count").value(12));
    }

    @Test
    @DisplayName("CSV 는 BOM 으로 시작하고 첨부로 내려간다")
    void exportsCsv() throws Exception {
        when(searchAuditLogsUseCase.export(any()))
                .thenReturn(new AuditLogExport(List.of(row()), false, 1));

        MvcResult result = mockMvc.perform(get("/admin/audit-logs/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andExpect(header().string("X-Export-Total", "1"))
                .andReturn();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        assertThat(body).contains("\"admin@lemuel.io\"");
        assertThat(result.getResponse().getHeader("Content-Disposition")).startsWith("attachment;");
    }

    @Test
    @DisplayName("상한에 걸려 잘리면 헤더로 알린다 — 잘린 줄 모르는 CSV 가 감사 자료로 나가면 안 된다")
    void exportAnnouncesTruncation() throws Exception {
        when(searchAuditLogsUseCase.export(any()))
                .thenReturn(new AuditLogExport(List.of(row()), true, 12_345));

        mockMvc.perform(get("/admin/audit-logs/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "true"))
                .andExpect(header().string("X-Export-Total", "12345"));
    }
}
