package github.lms.lemuel.review.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.review.application.ReviewService;
import github.lms.lemuel.review.domain.Review;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ReviewControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ReviewService reviewService;

    /** JWT 주체를 SecurityContext 에 직접 세팅(addFilters=false 슬라이스 대응). */
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

    @Test
    @DisplayName("POST /reviews creates review")
    void createReview() throws Exception {
        when(reviewService.createReview(1L, 2L, 5, "great"))
                .thenReturn(review(10L, 1L, 2L, 5, "great"));

        mockMvc.perform(post("/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":1,"userId":2,"rating":5,"content":"great"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.rating").value(5));
    }

    @Test
    @DisplayName("POST /reviews maps duplicate review to 409")
    void createReviewConflict() throws Exception {
        when(reviewService.createReview(1L, 2L, 5, "great"))
                .thenThrow(new IllegalStateException("duplicate"));

        mockMvc.perform(post("/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":1,"userId":2,"rating":5,"content":"great"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("duplicate"));
    }

    @Test
    @DisplayName("GET /reviews/product/{productId} returns product reviews")
    void getProductReviews() throws Exception {
        when(reviewService.getProductReviews(1L))
                .thenReturn(List.of(review(10L, 1L, 2L, 4, "good")));

        mockMvc.perform(get("/reviews/product/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].rating").value(4));
    }

    @Test
    @DisplayName("DELETE /reviews/{id} maps ownership failure to 403")
    void deleteReviewForbidden() throws Exception {
        doThrow(new IllegalStateException("forbidden"))
                .when(reviewService).deleteReview(10L, 2L);

        mockMvc.perform(delete("/reviews/10").param("userId", "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("forbidden"));
    }

    @Test
    @DisplayName("GET /reviews/user/{userId} - 본인 조회")
    void getUserReviews_self() throws Exception {
        login(2L, "USER");
        when(reviewService.getUserReviews(2L)).thenReturn(List.of());
        mockMvc.perform(get("/reviews/user/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /reviews/user/{userId} - 타인 조회는 403 (IDOR)")
    void getUserReviews_otherForbidden() throws Exception {
        login(3L, "USER");
        mockMvc.perform(get("/reviews/user/2"))
                .andExpect(status().isForbidden());
        verify(reviewService, never()).getUserReviews(any());
    }

    @Test
    @DisplayName("GET /reviews/user/{userId} - ADMIN 은 타인도 조회")
    void getUserReviews_adminBypass() throws Exception {
        login(999L, "ADMIN");
        when(reviewService.getUserReviews(2L)).thenReturn(List.of());
        mockMvc.perform(get("/reviews/user/2"))
                .andExpect(status().isOk());
    }

    private static Review review(Long id, Long productId, Long userId, int rating, String content) {
        Review review = Review.create(productId, userId, rating, content);
        review.assignId(id);
        return review;
    }
}
