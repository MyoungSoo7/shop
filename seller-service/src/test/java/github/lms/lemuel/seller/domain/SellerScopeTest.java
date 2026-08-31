package github.lms.lemuel.seller.domain;

import github.lms.lemuel.seller.domain.exception.InsufficientSellerRoleException;
import github.lms.lemuel.seller.domain.exception.NotASellerException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이 서비스의 인가는 전부 이 타입을 거친다 — 그래서 여기서 고정하는 것이 곧 보안 규칙이다.
 *
 * <p>파트너 콘솔의 같은 타입보다 여기서 더 중요한 이유는 실패 시 결과가 다르기 때문이다.
 * 거기서 스코프가 새면 남의 매출을 <i>보는</i> 것이지만, 여기서는 남의 이름으로 상품을 등록하고
 * 남의 주문에 송장을 찍는다. 읽기 IDOR 은 노출이고 쓰기 IDOR 은 위조다.
 */
class SellerScopeTest {

    private static SellerScope scope(OrgType type, Long sellerId, MemberRole role) {
        return new SellerScope(7L, "명수상사", type, sellerId, role);
    }

    @Test
    void 셀러_조직이면_셀러_ID_를_돌려준다() {
        assertEquals(777L, scope(OrgType.SELLER, 777L, MemberRole.OWNER).requireSellerId());
    }

    @Test
    void 셀러_ID_가_없으면_던진다() {
        // null 을 그대로 흘리면 조건이 seller_id IS NULL 이 되어 셀러 미할당 데이터 전체가
        // 한 조직에 열린다. 빈 값을 조회 조건으로 쓰지 않는다는 규칙을 여기서 고정한다.
        NotASellerException thrown = assertThrows(NotASellerException.class,
                () -> scope(OrgType.CORPORATE, null, MemberRole.OWNER).requireSellerId());
        assertTrue(thrown.getMessage().contains("7"), thrown.getMessage());
    }

    @Test
    void 제출_권한은_셀러_확인까지_함께_한다() {
        // 법인 조직의 OWNER 는 역할만 보면 통과한다. 두 검사를 따로 두면 호출부에서 역할만
        // 보는 코드가 반드시 생기고, 그때 이 조합이 그대로 뚫린다.
        assertThrows(NotASellerException.class,
                () -> scope(OrgType.CORPORATE, null, MemberRole.OWNER).requireSubmitPermission());
    }

    @Test
    void STAFF_는_제출할_수_없다() {
        InsufficientSellerRoleException thrown = assertThrows(InsufficientSellerRoleException.class,
                () -> scope(OrgType.SELLER, 777L, MemberRole.STAFF).requireSubmitPermission());
        assertTrue(thrown.getMessage().contains("STAFF"), thrown.getMessage());
    }

    @Test
    void OWNER_와_MANAGER_는_제출할_수_있다() {
        assertEquals(777L, scope(OrgType.SELLER, 777L, MemberRole.OWNER).requireSubmitPermission());
        assertEquals(777L, scope(OrgType.SELLER, 777L, MemberRole.MANAGER).requireSubmitPermission());
    }

    @Test
    void STAFF_도_조회는_할_수_있다() {
        // 역할 제한은 "밖으로 나가는 행위" 에만 건다. 조회까지 막으면 STAFF 는 콘솔을 열 수조차
        // 없고, 그러면 조직들이 STAFF 를 안 쓰고 전원 MANAGER 로 만든다 — 통제가 사라진다.
        assertEquals(777L, scope(OrgType.SELLER, 777L, MemberRole.STAFF).requireSellerId());
    }

    @Test
    void canSubmit_은_STAFF_만_거른다() {
        assertTrue(MemberRole.OWNER.canSubmit());
        assertTrue(MemberRole.MANAGER.canSubmit());
        assertFalse(MemberRole.STAFF.canSubmit());
    }
}
