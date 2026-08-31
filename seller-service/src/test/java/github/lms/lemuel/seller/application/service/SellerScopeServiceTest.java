package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SellerMemberView;
import github.lms.lemuel.seller.application.port.dto.SellerProfileView;
import github.lms.lemuel.seller.application.port.out.LoadSellerScopePort;
import github.lms.lemuel.seller.domain.MemberRole;
import github.lms.lemuel.seller.domain.OrgType;
import github.lms.lemuel.seller.domain.SellerScope;
import github.lms.lemuel.seller.domain.exception.SellerScopeNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이 서비스가 이 모듈의 인가 경계 전부다 — 다른 유스케이스는 여기서 나온 스코프만 받는다.
 *
 * <p>고정하는 것은 셋이다. ① 입구가 {@code userId} 하나뿐이고 없으면 던진다, ② 구성원 조회
 * 인자가 스코프에서만 온다, ③ {@code canSubmit} 을 화면이 조합하지 않는다.
 */
class SellerScopeServiceTest {

    private LoadSellerScopePort scopePort;
    private SellerScopeService service;

    @BeforeEach
    void setUp() {
        scopePort = mock(LoadSellerScopePort.class);
        service = new SellerScopeService(scopePort);
    }

    private static SellerScope seller(MemberRole role) {
        return new SellerScope(7L, "명수상사", OrgType.SELLER, 777L, role);
    }

    private static SellerScope corporate() {
        return new SellerScope(9L, "르무엘법인", OrgType.CORPORATE, null, MemberRole.OWNER);
    }

    @Test
    void 소속이_있으면_그_스코프를_돌려준다() {
        when(scopePort.findByUserId(42L)).thenReturn(Optional.of(seller(MemberRole.OWNER)));

        assertEquals(seller(MemberRole.OWNER), service.resolve(42L));
    }

    @Test
    void 어느_조직에도_없으면_던진다() {
        when(scopePort.findByUserId(42L)).thenReturn(Optional.empty());

        // 일반 회원이 URL 을 직접 연 경우가 대부분이지만, 방금 초대된 사람이 member_joined 도착
        // 전에 만나는 짧은 창도 여기로 온다. 그래서 예외는 "권한 없음" 이 아니라 "미소속" 이다.
        SellerScopeNotFoundException thrown =
                assertThrows(SellerScopeNotFoundException.class, () -> service.resolve(42L));
        assertTrue(thrown.getMessage().contains("userId=42"), thrown.getMessage());
    }

    @Test
    void 프로필은_등록_가능_여부를_직접_계산해_준다() {
        SellerProfileView view = service.profile(seller(MemberRole.MANAGER));

        assertEquals(7L, view.organizationId());
        assertEquals("명수상사", view.organizationName());
        assertEquals(OrgType.SELLER, view.orgType());
        assertEquals(777L, view.sellerId());
        assertEquals(MemberRole.MANAGER, view.myRole());
        assertTrue(view.canSubmit());
    }

    @Test
    void STAFF_는_등록_버튼이_꺼진다() {
        assertFalse(service.profile(seller(MemberRole.STAFF)).canSubmit());
    }

    @Test
    void 법인_조직은_OWNER_라도_등록_버튼이_꺼진다() {
        // 화면에서 "셀러인가" 와 "역할이 되는가" 를 따로 조합하게 두면 이 조합에서 버튼이 켜지고,
        // 누른 뒤에야 422 를 받는다. 규칙을 한 군데에 두는 것이 요점이다.
        SellerProfileView view = service.profile(corporate());

        assertFalse(view.canSubmit());
        assertNull(view.sellerId());
        assertEquals(OrgType.CORPORATE, view.orgType());
    }

    @Test
    void 구성원은_스코프의_조직으로만_조회한다() {
        SellerMemberView member = new SellerMemberView(
                1L, 42L, MemberRole.OWNER,
                OffsetDateTime.of(2026, 9, 1, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
        when(scopePort.findActiveMembers(7L)).thenReturn(List.of(member));

        assertEquals(List.of(member), service.members(seller(MemberRole.STAFF)));
        // 인자가 스코프에서만 온다는 것이 요점이다. 요청에서 받은 조직 ID 가 들어오면 그게 IDOR 이다.
        verify(scopePort).findActiveMembers(7L);
    }
}
