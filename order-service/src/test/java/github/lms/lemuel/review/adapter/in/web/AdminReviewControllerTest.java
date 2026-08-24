package github.lms.lemuel.review.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.review.application.port.in.ModerateReviewUseCase;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewExport;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewPage;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewQuery;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewRow;
import github.lms.lemuel.review.application.port.in.SearchReviewsUseCase.ReviewStatusCount;
import github.lms.lemuel.review.domain.Review;
import github.lms.lemuel.review.domain.ReviewStatus;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리뷰 콘솔의 HTTP 표면.
 *
 * <p>여기서 지키는 것: 필터가 질의로 온전히 옮겨지는가, 사유 없는 블라인드가 400 으로 막히는가,
 * 그리고 <b>삭제 엔드포인트가 없는가</b>. 마지막은 "생기지 않았음"을 지키는 테스트다 —
 * 지울 수 있는 버튼이 생기면 언젠가 눌리고, 그 뒤엔 이의 제기에 답할 원문이 없다.
 */
@WebMvcTest(controllers = AdminReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminReviewControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean SearchReviewsUseCase searchReviewsUseCase;
    @MockitoBean ModerateReviewUseCase moderateReviewUseCase;
    @MockitoBean LoadUserPort loadUserPort;

    private static ReviewRow row() {
        return new ReviewRow(11L, 2L, "무선 이어폰", 3L, "hong@lemuel.io", 1,
                "최악입니다", "VISIBLE", null, null, null, LocalDateTime.of(2026, 3, 1, 12, 0));
    }

    @Test
    @DisplayName("GET /admin/reviews — 상품명·작성자까지 담아 돌려준다")
    void search() throws Exception {
        when(searchReviewsUseCase.search(any()))
                .thenReturn(new ReviewPage(List.of(row()), 0, 50, 1, 1));

        mockMvc.perform(get("/admin/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].productName").value("무선 이어폰"))
                .andExpect(jsonPath("$.content[0].userEmail").value("hong@lemuel.io"))
                .andExpect(jsonPath("$.content[0].rating").value(1));
    }

    @Test
    @DisplayName("필터 파라미터는 그대로 질의가 된다")
    void passesFiltersThrough() throws Exception {
        when(searchReviewsUseCase.search(any())).thenReturn(new ReviewPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/reviews")
                        .param("keyword", "최악")
                        .param("productId", "2")
                        .param("userId", "3")
                        .param("status", "HIDDEN")
                        .param("maxRating", "2")
                        .param("from", "2026-03-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReviewQuery> captor = ArgumentCaptor.forClass(ReviewQuery.class);
        verify(searchReviewsUseCase).search(captor.capture());
        ReviewQuery query = captor.getValue();
        assertThat(query.keyword()).isEqualTo("최악");
        assertThat(query.productId()).isEqualTo(2L);
        assertThat(query.userId()).isEqualTo(3L);
        assertThat(query.status()).isEqualTo(ReviewStatus.HIDDEN);
        assertThat(query.maxRating()).isEqualTo(2);
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("모르는 상태 이름은 조건에서 뺀다 — 오타 하나가 목록을 통째로 비우면 안 된다")
    void unknownStatusDropsFilter() throws Exception {
        when(searchReviewsUseCase.search(any())).thenReturn(new ReviewPage(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/admin/reviews").param("status", "BLIND"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReviewQuery> captor = ArgumentCaptor.forClass(ReviewQuery.class);
        verify(searchReviewsUseCase).search(captor.capture());
        assertThat(captor.getValue().status()).isNull();
    }

    @Test
    @DisplayName("상태별 집계에는 상태 필터를 걸지 않는다")
    void statusCountsIgnoreStatusFilter() throws Exception {
        when(searchReviewsUseCase.countByStatus(any()))
                .thenReturn(List.of(new ReviewStatusCount("HIDDEN", 3)));

        mockMvc.perform(get("/admin/reviews/status-counts").param("maxRating", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("HIDDEN"));

        ArgumentCaptor<ReviewQuery> captor = ArgumentCaptor.forClass(ReviewQuery.class);
        verify(searchReviewsUseCase).countByStatus(captor.capture());
        assertThat(captor.getValue().status()).isNull();
        assertThat(captor.getValue().maxRating()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /admin/reviews/statuses — 서버 enum 이 필터 목록의 정본이다")
    void statusesAreServerOwned() throws Exception {
        mockMvc.perform(get("/admin/reviews/statuses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasItems("VISIBLE", "HIDDEN")));
    }

    @Test
    @DisplayName("블라인드는 사유와 함께 유스케이스로 넘어간다")
    void hide() throws Exception {
        Review hidden = Review.rehydrate(11L, 2L, 3L, 1, "최악입니다",
                LocalDateTime.of(2026, 3, 1, 12, 0), LocalDateTime.of(2026, 3, 2, 9, 0),
                ReviewStatus.HIDDEN, "욕설 신고", 9L, LocalDateTime.of(2026, 3, 2, 9, 0));
        when(moderateReviewUseCase.hide(anyLong(), any(), any())).thenReturn(hidden);

        mockMvc.perform(post("/admin/reviews/11/hide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"욕설 신고\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"))
                .andExpect(jsonPath("$.hiddenReason").value("욕설 신고"));

        verify(moderateReviewUseCase).hide(eq(11L), eq("욕설 신고"), any());
    }

    @Test
    @DisplayName("사유 없는 블라인드는 400 이다 — 유스케이스까지 가지 않는다")
    void hideWithoutReasonIsRejected() throws Exception {
        mockMvc.perform(post("/admin/reviews/11/hide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("해제는 사유 없이 부른다")
    void restore() throws Exception {
        Review restored = Review.rehydrate(11L, 2L, 3L, 1, "최악입니다",
                LocalDateTime.of(2026, 3, 1, 12, 0), LocalDateTime.of(2026, 3, 3, 9, 0),
                ReviewStatus.VISIBLE, null, null, null);
        when(moderateReviewUseCase.restore(anyLong(), any())).thenReturn(restored);

        mockMvc.perform(post("/admin/reviews/11/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VISIBLE"))
                .andExpect(jsonPath("$.hiddenReason").doesNotExist());
    }

    @Test
    @DisplayName("삭제 엔드포인트는 존재하지 않는다 — 운영자에게 필요한 것은 노출 차단이지 말소가 아니다")
    void hasNoDeleteEndpoint() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/reviews/11"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(404, 405));
    }

    @Test
    @DisplayName("CSV 는 BOM 으로 시작하고 잘림 여부를 헤더로 알린다")
    void exportsCsv() throws Exception {
        when(searchReviewsUseCase.export(any()))
                .thenReturn(new ReviewExport(List.of(row()), false, 1));

        MvcResult result = mockMvc.perform(get("/admin/reviews/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andReturn();

        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        assertThat(body).contains("\"무선 이어폰\"").contains("\"최악입니다\"");
    }
}
