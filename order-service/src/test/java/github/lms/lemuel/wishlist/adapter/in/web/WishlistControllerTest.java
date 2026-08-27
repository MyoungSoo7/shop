package github.lms.lemuel.wishlist.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.wishlist.application.port.in.WishlistUseCase;
import github.lms.lemuel.wishlist.domain.Wishlist;
import github.lms.lemuel.wishlist.domain.WishlistAvailability;
import github.lms.lemuel.wishlist.domain.WishlistEntry;
import github.lms.lemuel.wishlist.domain.WishlistItem;
import github.lms.lemuel.wishlist.domain.WishlistProduct;
import github.lms.lemuel.wishlist.domain.exception.WishlistInvariantViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WishlistController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("찜 컨트롤러")
class WishlistControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean WishlistUseCase wishlistUseCase;

    private static void login(long uid, String role) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(uid, uid + "@x.com", role),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static WishlistEntry entry(long productId, WishlistAvailability availability) {
        return new WishlistEntry(
                WishlistItem.rehydrate(productId, 1L, productId, LocalDateTime.now()),
                new WishlistProduct(productId, "상품 " + productId,
                        new BigDecimal("1000"), availability, "https://img/" + productId));
    }

    private static Wishlist mixedWishlist() {
        return new Wishlist(1L, List.of(
                entry(10L, WishlistAvailability.AVAILABLE),
                entry(11L, WishlistAvailability.OUT_OF_STOCK),
                entry(12L, WishlistAvailability.DISCONTINUED)));
    }

    // ------------------------------------------------------------ 조회

    @Test
    @DisplayName("GET: 살 수 없는 항목도 사유와 함께 그대로 내려온다")
    void listIncludesUnavailableWithReason() {
        login(1L, "USER");
        when(wishlistUseCase.list(1L)).thenReturn(mixedWishlist());

        assertOk(() -> mockMvc.perform(get("/users/1/wishlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.goneCount").value(1))
                .andExpect(jsonPath("$.maxItems").value(Wishlist.MAX_ITEMS))
                .andExpect(jsonPath("$.items[1].availability").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.items[1].reason")
                        .value(WishlistAvailability.OUT_OF_STOCK.label()))
                .andExpect(jsonPath("$.items[1].available").value(false))
                .andExpect(jsonPath("$.items[1].gone").value(false))
                .andExpect(jsonPath("$.items[2].gone").value(true)));
    }

    @Test
    @DisplayName("GET: 빈 목록도 200 이고 상한을 함께 알려 준다")
    void emptyListIsOk() {
        login(1L, "USER");
        when(wishlistUseCase.list(1L)).thenReturn(Wishlist.empty(1L));

        assertOk(() -> mockMvc.perform(get("/users/1/wishlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.goneCount").value(0)));
    }

    // ------------------------------------------------------- 담기 · 빼기

    @Test
    @DisplayName("PUT: 담으면 결과 상태를 돌려준다 (방금 한 동작이 아니라)")
    void addReturnsResultingState() {
        login(1L, "USER");
        when(wishlistUseCase.add(1L, 10L)).thenReturn(new WishlistUseCase.Mutation(true, true, 3));

        assertOk(() -> mockMvc.perform(put("/users/1/wishlist/products/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wished").value(true))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.count").value(3)));
    }

    @Test
    @DisplayName("PUT: 이미 담긴 것을 또 담아도 200 — changed 만 false")
    void addIsIdempotent() {
        login(1L, "USER");
        when(wishlistUseCase.add(1L, 10L)).thenReturn(new WishlistUseCase.Mutation(true, false, 3));

        assertOk(() -> mockMvc.perform(put("/users/1/wishlist/products/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wished").value(true))
                .andExpect(jsonPath("$.changed").value(false)));
    }

    @Test
    @DisplayName("DELETE: 담겨 있지 않아도 200")
    void removeIsIdempotent() {
        login(1L, "USER");
        when(wishlistUseCase.remove(1L, 10L)).thenReturn(new WishlistUseCase.Mutation(false, false, 3));

        assertOk(() -> mockMvc.perform(delete("/users/1/wishlist/products/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wished").value(false))
                .andExpect(jsonPath("$.changed").value(false)));
    }

    @Test
    @DisplayName("PUT: 상한 초과는 400 — 서버 오류가 아니라 사용자에게 설명 가능한 거부다")
    void addBeyondLimitIsBadRequest() {
        login(1L, "USER");
        when(wishlistUseCase.add(1L, 10L))
                .thenThrow(new WishlistInvariantViolationException("찜은 최대 300개까지 담을 수 있습니다."));

        assertOk(() -> mockMvc.perform(put("/users/1/wishlist/products/10"))
                .andExpect(status().isBadRequest()));
    }

    @Test
    @DisplayName("GET 단건: 하트 표시용 — 목록 전체를 부르지 않는다")
    void containsUsesSingleLookup() {
        login(1L, "USER");
        when(wishlistUseCase.contains(1L, 10L)).thenReturn(true);

        assertOk(() -> mockMvc.perform(get("/users/1/wishlist/products/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(10))
                .andExpect(jsonPath("$.wished").value(true)));
        verify(wishlistUseCase, never()).list(anyLong());
    }

    // ---------------------------------------------------------- 일괄 정리

    @Test
    @DisplayName("DELETE /gone: 지운 목록을 그대로 돌려준다 — 개수만 주면 무엇이 사라졌는지 말할 수 없다")
    void purgeReturnsWhatWasRemoved() {
        login(1L, "USER");
        when(wishlistUseCase.purgeGone(1L)).thenReturn(new WishlistUseCase.PurgeResult(
                List.of(entry(12L, WishlistAvailability.DISCONTINUED)),
                new Wishlist(1L, List.of(
                        entry(10L, WishlistAvailability.AVAILABLE),
                        entry(11L, WishlistAvailability.OUT_OF_STOCK)))));

        assertOk(() -> mockMvc.perform(delete("/users/1/wishlist/gone"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed[0].productId").value(12))
                .andExpect(jsonPath("$.removed[0].reason")
                        .value(WishlistAvailability.DISCONTINUED.label()))
                // 품절은 남아 있어야 한다 — 재입고를 기다리는 것이 찜의 목적이다.
                .andExpect(jsonPath("$.wishlist.totalCount").value(2))
                .andExpect(jsonPath("$.wishlist.items[1].availability").value("OUT_OF_STOCK")));
    }

    // ------------------------------------------------------- 소유권(IDOR)

    @Test
    @DisplayName("남의 찜 조회는 403")
    void otherUsersWishlistForbidden() {
        login(2L, "USER");

        assertOk(() -> mockMvc.perform(get("/users/1/wishlist"))
                .andExpect(status().isForbidden()));
        verify(wishlistUseCase, never()).list(any());
    }

    @Test
    @DisplayName("남의 찜에 담기도 403 — 경로의 userId 를 믿지 않는다")
    void addingToOthersWishlistForbidden() {
        login(2L, "USER");

        assertOk(() -> mockMvc.perform(put("/users/1/wishlist/products/10"))
                .andExpect(status().isForbidden()));
        verify(wishlistUseCase, never()).add(any(), any());
    }

    @Test
    @DisplayName("남의 찜 일괄 정리는 403 — 가장 파괴적인 경로라 특히 막는다")
    void purgingOthersWishlistForbidden() {
        login(2L, "USER");

        assertOk(() -> mockMvc.perform(delete("/users/1/wishlist/gone"))
                .andExpect(status().isForbidden()));
        verify(wishlistUseCase, never()).purgeGone(any());
    }

    @Test
    @DisplayName("미인증 요청은 403")
    void unauthenticatedForbidden() {
        assertOk(() -> mockMvc.perform(get("/users/1/wishlist"))
                .andExpect(status().isForbidden()));
    }

    @Test
    @DisplayName("ADMIN 은 타인 찜도 조회 가능(운영 지원)")
    void adminCanViewOthers() {
        login(999L, "ADMIN");
        when(wishlistUseCase.list(1L)).thenReturn(Wishlist.empty(1L));

        assertOk(() -> mockMvc.perform(get("/users/1/wishlist")).andExpect(status().isOk()));
    }

    /** MockMvc 의 checked exception 을 람다로 감싸 테스트 본문을 읽기 좋게 유지한다. */
    private static void assertOk(ThrowingRunnable body) {
        try {
            body.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
