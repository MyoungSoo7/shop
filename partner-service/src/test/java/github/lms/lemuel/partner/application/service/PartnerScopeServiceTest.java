package github.lms.lemuel.partner.application.service;

import github.lms.lemuel.partner.application.port.dto.PartnerMemberView;
import github.lms.lemuel.partner.application.port.dto.PartnerProfileView;
import github.lms.lemuel.partner.application.port.out.LoadPartnerScopePort;
import github.lms.lemuel.partner.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.partner.domain.MemberRole;
import github.lms.lemuel.partner.domain.OrgType;
import github.lms.lemuel.partner.domain.PartnerScope;
import github.lms.lemuel.partner.domain.SellerTier;
import github.lms.lemuel.partner.domain.exception.PartnerScopeNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 이 서비스가 이 모듈의 인가 경계 전부다 — 다른 유스케이스는 여기서 나온 스코프만 받는다.
 *
 * <p>그래서 여기서 고정하는 것은 세 가지다. ① 입구가 {@code userId} 하나뿐이고 없으면 던진다,
 * ② 등급은 셀러에게만 묻는다, ③ 모르는 등급을 NORMAL 로 채우지 않는다.
 */
class PartnerScopeServiceTest {

    private LoadPartnerScopePort scopePort;
    private LoadSellerTierPort tierPort;
    private PartnerScopeService service;

    @BeforeEach
    void setUp() {
        scopePort = mock(LoadPartnerScopePort.class);
        tierPort = mock(LoadSellerTierPort.class);
        service = new PartnerScopeService(scopePort, tierPort);
    }

    private static PartnerScope seller() {
        return new PartnerScope(7L, "명수상사", OrgType.SELLER, 777L, MemberRole.OWNER);
    }

    private static PartnerScope corporate() {
        return new PartnerScope(9L, "르무엘법인", OrgType.CORPORATE, null, MemberRole.STAFF);
    }

    @Test
    void 소속이_있으면_그_스코프를_돌려준다() {
        when(scopePort.findByUserId(42L)).thenReturn(Optional.of(seller()));

        assertEquals(seller(), service.resolve(42L));
    }

    @Test
    void 어느_조직에도_없으면_던진다() {
        when(scopePort.findByUserId(42L)).thenReturn(Optional.empty());

        // 일반 회원이 URL 을 직접 연 경우가 대부분이지만, 방금 초대된 사람이 member_joined 도착
        // 전에 만나는 짧은 창도 여기로 온다. 그래서 예외는 "권한 없음" 이 아니라 "미소속" 이다.
        PartnerScopeNotFoundException thrown =
                assertThrows(PartnerScopeNotFoundException.class, () -> service.resolve(42L));
        assertTrue(thrown.getMessage().contains("userId=42"), thrown.getMessage());
    }

    @Test
    void 셀러는_현재_등급을_함께_보여_준다() {
        when(tierPort.findBySellerId(777L)).thenReturn(Optional.of(
                new LoadSellerTierPort.TierSnapshot(SellerTier.VIP, LocalDate.of(2026, 8, 1))));

        PartnerProfileView view = service.profile(seller());

        assertEquals(7L, view.organizationId());
        assertEquals("명수상사", view.organizationName());
        assertEquals(OrgType.SELLER, view.orgType());
        assertEquals(777L, view.sellerId());
        assertEquals(MemberRole.OWNER, view.myRole());
        assertTrue(view.salesAvailable());
        assertEquals(SellerTier.VIP, view.currentTier());
        assertEquals(LocalDate.of(2026, 8, 1), view.tierEffectiveFrom());
    }

    @Test
    void 등급_이벤트가_아직_없으면_미확인으로_둔다() {
        when(tierPort.findBySellerId(777L)).thenReturn(Optional.empty());

        PartnerProfileView view = service.profile(seller());

        // null 은 "미확인" 이고 NORMAL 은 "일반 등급이라고 확인됨" 이다. 여기서 NORMAL 로 메우면
        // 화면이 확인되지 않은 것을 확인된 것처럼 말하게 된다.
        assertNull(view.currentTier());
        assertNull(view.tierEffectiveFrom());
        assertTrue(view.salesAvailable());
    }

    @Test
    void 법인_조직에는_등급을_묻지도_않는다() {
        PartnerProfileView view = service.profile(corporate());

        // 물으면 항상 비고, 그 빈 값이 화면에서 "등급 미확인" 으로 보여 법인 고객에게 없는
        // 문제를 있는 것처럼 만든다. 조회 자체를 하지 않는 것이 이 규칙의 실체다.
        verifyNoInteractions(tierPort);
        assertFalse(view.salesAvailable());
        assertNull(view.sellerId());
        assertNull(view.currentTier());
        assertEquals(OrgType.CORPORATE, view.orgType());
    }

    @Test
    void 구성원은_스코프의_조직으로만_조회한다() {
        PartnerMemberView member = new PartnerMemberView(
                1L, 42L, MemberRole.OWNER, OffsetDateTime.of(2026, 8, 1, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
        when(scopePort.findActiveMembers(7L)).thenReturn(List.of(member));

        assertEquals(List.of(member), service.members(seller()));
        // 인자가 스코프에서만 온다는 것이 요점이다. 요청에서 받은 조직 ID 가 들어오면 그게 IDOR 이다.
        verify(scopePort).findActiveMembers(7L);
    }
}
