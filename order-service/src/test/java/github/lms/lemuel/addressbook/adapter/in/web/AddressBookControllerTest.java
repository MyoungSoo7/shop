package github.lms.lemuel.addressbook.adapter.in.web;

import github.lms.lemuel.addressbook.application.port.in.AddressBookUseCase;
import github.lms.lemuel.addressbook.application.port.in.AddressBookUseCase.AddressForm;
import github.lms.lemuel.addressbook.domain.AddressBook;
import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;
import github.lms.lemuel.addressbook.domain.exception.AddressBookInvariantViolationException;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AddressBookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("배송지 주소록 컨트롤러")
class AddressBookControllerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 27, 10, 0, 0);

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean AddressBookUseCase addressBookUseCase;

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

    private static ShippingAddressEntry entry(long id, String label, boolean isDefault) {
        return new ShippingAddressEntry(id, 1L, label, "홍길동", "010-1234-5678",
                "06236", "서울 강남구 테헤란로 1", "301호", "문 앞에", isDefault, T0, T0);
    }

    private static AddressBook book() {
        return new AddressBook(1L, List.of(entry(1L, "집", true), entry(2L, "회사", false)));
    }

    /**
     * 요청 본문.
     *
     * <p>레코드를 직렬화하지 않고 JSON 을 그대로 적는다 — 같은 레코드로 만들어 같은 레코드로 읽으면
     * 필드 이름이 통째로 바뀌어도 테스트는 통과한다. 화면과 주고받는 것은 이 글자들이다.
     */
    private static String json(String label, boolean makeDefault) {
        return """
                {"label": "%s", "recipientName": "홍길동", "phone": "010-1234-5678",
                 "postalCode": "06236", "address1": "서울 강남구 테헤란로 1",
                 "address2": "301호", "deliveryMemo": "문 앞에", "makeDefault": %s}
                """.formatted(label, makeDefault);
    }

    // ------------------------------------------------------------ 조회

    @Test
    @DisplayName("GET: 기본 배송지가 맨 위로 내려오고 상한을 함께 알려 준다")
    void listPutsDefaultFirst() {
        login(1L, "USER");
        when(addressBookUseCase.list(1L)).thenReturn(book());

        assertOk(() -> mockMvc.perform(get("/users/1/shipping-addresses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.maxAddresses").value(AddressBook.MAX_ENTRIES))
                .andExpect(jsonPath("$.addresses[0].id").value(1))
                .andExpect(jsonPath("$.addresses[0].isDefault").value(true))
                .andExpect(jsonPath("$.addresses[1].isDefault").value(false)));
    }

    @Test
    @DisplayName("GET: 별칭과 받는 분이 각각 다른 칸으로 내려온다")
    void labelAndRecipientAreSeparateFields() {
        login(1L, "USER");
        when(addressBookUseCase.list(1L)).thenReturn(
                new AddressBook(1L, List.of(entry(1L, "회사", true))));

        assertOk(() -> mockMvc.perform(get("/users/1/shipping-addresses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses[0].label").value("회사"))
                .andExpect(jsonPath("$.addresses[0].recipientName").value("홍길동")));
    }

    @Test
    @DisplayName("GET /default: 기본 배송지 한 줄")
    void findDefaultReturnsOne() {
        login(1L, "USER");
        when(addressBookUseCase.findDefault(1L)).thenReturn(Optional.of(entry(1L, "집", true)));

        assertOk(() -> mockMvc.perform(get("/users/1/shipping-addresses/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.isDefault").value(true)));
    }

    @Test
    @DisplayName("GET /default: 주소록이 비어 있으면 204 — 빈 본문 200 이 아니다")
    void findDefaultOnEmptyBookIsNoContent() {
        login(1L, "USER");
        when(addressBookUseCase.findDefault(1L)).thenReturn(Optional.empty());

        assertOk(() -> mockMvc.perform(get("/users/1/shipping-addresses/default"))
                .andExpect(status().isNoContent()));
    }

    // ------------------------------------------------------- 등록 · 수정

    @Test
    @DisplayName("POST: 첫 배송지는 makeDefault 를 보내지 않아도 기본으로 돌아온다")
    void registerFirstBecomesDefault() {
        login(1L, "USER");
        when(addressBookUseCase.register(eq(1L), any())).thenReturn(entry(1L, "집", true));

        assertOk(() -> mockMvc.perform(post("/users/1/shipping-addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("집", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true)));
    }

    @Test
    @DisplayName("POST: 본문의 모든 칸이 폼으로 그대로 전달된다")
    void registerPassesEveryField() {
        login(1L, "USER");
        when(addressBookUseCase.register(eq(1L), any())).thenReturn(entry(1L, "회사", false));

        assertOk(() -> mockMvc.perform(post("/users/1/shipping-addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"label": "회사", "recipientName": "김철수", "phone": "010-9999-9999",
                         "postalCode": "13529", "address1": "경기 성남시", "address2": "8층",
                         "deliveryMemo": "부재시 경비실", "makeDefault": true}
                        """))
                .andExpect(status().isOk()));

        verify(addressBookUseCase).register(1L, new AddressForm("회사", "김철수", "010-9999-9999",
                "13529", "경기 성남시", "8층", "부재시 경비실", true));
    }

    @Test
    @DisplayName("POST: 필수 항목 누락은 400 — 서버 오류가 아니라 설명 가능한 거부다")
    void registerWithBlankRequiredFieldIsBadRequest() {
        login(1L, "USER");
        when(addressBookUseCase.register(eq(1L), any()))
                .thenThrow(new AddressBookInvariantViolationException("받는 분 — 필수 항목입니다."));

        assertOk(() -> mockMvc.perform(post("/users/1/shipping-addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("집", false)))
                .andExpect(status().isBadRequest()));
    }

    @Test
    @DisplayName("PUT: 수정한 줄을 돌려준다")
    void modifyReturnsUpdatedEntry() {
        login(1L, "USER");
        when(addressBookUseCase.modify(eq(1L), eq(2L), any())).thenReturn(entry(2L, "우리집", false));

        assertOk(() -> mockMvc.perform(put("/users/1/shipping-addresses/2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("우리집", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.label").value("우리집")));
    }

    // ------------------------------------------------------- 기본 · 삭제

    @Test
    @DisplayName("PUT /default: 한 번의 호출이고 응답은 주소록 전체다")
    void setDefaultIsOneCallReturningWholeBook() {
        // 레거시는 '전부 내리기'와 '하나 올리기'가 서로 다른 요청이라, 사이에서 끊기면
        // 기본이 하나도 없는 상태로 남았다. 화면이 순서를 책임지지 않게 한 번으로 묶었다.
        login(1L, "USER");
        when(addressBookUseCase.setDefault(1L, 2L)).thenReturn(
                new AddressBook(1L, List.of(entry(2L, "회사", true), entry(1L, "집", false))));

        assertOk(() -> mockMvc.perform(put("/users/1/shipping-addresses/2/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses[0].id").value(2))
                .andExpect(jsonPath("$.addresses[0].isDefault").value(true))
                .andExpect(jsonPath("$.addresses[1].isDefault").value(false)));
    }

    @Test
    @DisplayName("DELETE: 삭제 후 주소록 전체를 돌려준다 — 승격 결과를 다시 묻지 않아도 된다")
    void removeReturnsRemainingBook() {
        login(1L, "USER");
        when(addressBookUseCase.remove(1L, 1L)).thenReturn(
                new AddressBook(1L, List.of(entry(2L, "회사", true))));

        assertOk(() -> mockMvc.perform(delete("/users/1/shipping-addresses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.addresses[0].id").value(2))
                .andExpect(jsonPath("$.addresses[0].isDefault").value(true)));
    }

    @Test
    @DisplayName("DELETE: 마지막 줄을 지우면 빈 주소록도 200 이다")
    void removingLastLeavesEmptyBook() {
        login(1L, "USER");
        when(addressBookUseCase.remove(1L, 1L)).thenReturn(AddressBook.empty(1L));

        assertOk(() -> mockMvc.perform(delete("/users/1/shipping-addresses/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0)));
    }

    // ------------------------------------------------------- 소유권(IDOR)

    @Test
    @DisplayName("남의 주소록 조회는 403")
    void otherUsersBookForbidden() {
        login(2L, "USER");

        assertOk(() -> mockMvc.perform(get("/users/1/shipping-addresses"))
                .andExpect(status().isForbidden()));
        verify(addressBookUseCase, never()).list(any());
    }

    @Test
    @DisplayName("남의 주소록에 등록도 403 — 경로의 userId 를 믿지 않는다")
    void registeringForOthersForbidden() {
        login(2L, "USER");

        assertOk(() -> mockMvc.perform(post("/users/1/shipping-addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("집", false)))
                .andExpect(status().isForbidden()));
        verify(addressBookUseCase, never()).register(any(), any());
    }

    @Test
    @DisplayName("남의 배송지 삭제는 403")
    void deletingOthersForbidden() {
        login(2L, "USER");

        assertOk(() -> mockMvc.perform(delete("/users/1/shipping-addresses/1"))
                .andExpect(status().isForbidden()));
        verify(addressBookUseCase, never()).remove(any(), any());
    }

    @Test
    @DisplayName("남의 기본 배송지 지정도 403")
    void settingOthersDefaultForbidden() {
        login(2L, "USER");

        assertOk(() -> mockMvc.perform(put("/users/1/shipping-addresses/1/default"))
                .andExpect(status().isForbidden()));
        verify(addressBookUseCase, never()).setDefault(any(), any());
    }

    @Test
    @DisplayName("미인증 요청은 403")
    void unauthenticatedForbidden() {
        assertOk(() -> mockMvc.perform(get("/users/1/shipping-addresses"))
                .andExpect(status().isForbidden()));
    }

    @Test
    @DisplayName("ADMIN 은 타인 주소록도 조회 가능(운영 지원)")
    void adminCanViewOthers() {
        login(999L, "ADMIN");
        when(addressBookUseCase.list(1L)).thenReturn(AddressBook.empty(1L));

        assertOk(() -> mockMvc.perform(get("/users/1/shipping-addresses"))
                .andExpect(status().isOk()));
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
