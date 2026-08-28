package github.lms.lemuel.partner.domain;

import github.lms.lemuel.partner.domain.exception.NoSalesScopeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이 타입의 시험대는 {@code requireSellerId()} 하나다.
 *
 * <p>null 을 그대로 흘리면 조회 조건이 {@code seller_id IS NULL} 이 되어 <b>셀러 미할당 결제
 * 전체</b>가 한 조직에 보인다. 화면은 정상으로 보이고 숫자도 나오므로 아무도 신고하지 않는다 —
 * 조용한 정보 노출이다. 그래서 "던지는가" 만 보지 않고 <b>어느 쪽 사유로 던지는가</b>까지 고정한다.
 * 두 사유는 대응이 다르다: CORPORATE 는 정상이고, SELLER 는 운영자가 데이터를 봐야 한다.
 */
class PartnerScopeTest {

    private static PartnerScope scope(OrgType type, Long sellerId) {
        return new PartnerScope(7L, "명수상사", type, sellerId, MemberRole.OWNER);
    }

    @Test
    void 셀러_ID_가_있으면_그대로_돌려준다() {
        assertEquals(777L, scope(OrgType.SELLER, 777L).requireSellerId());
    }

    @Test
    void 법인_조직은_매출_개념이_없다고_말한다() {
        NoSalesScopeException thrown = assertThrows(NoSalesScopeException.class,
                () -> scope(OrgType.CORPORATE, null).requireSellerId());

        // 문구가 사유를 가른다. "판매 조직이 아니다" 는 정상이고 고칠 것이 없다.
        assertTrue(thrown.getMessage().contains("판매 조직이 아니어서"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("organizationId=7"), thrown.getMessage());
    }

    @Test
    void 셀러인데_ID_가_없으면_데이터_문제라고_말한다() {
        NoSalesScopeException thrown = assertThrows(NoSalesScopeException.class,
                () -> scope(OrgType.SELLER, null).requireSellerId());

        // 이쪽은 externalRef 파싱 실패다 — 운영자가 봐야 하는 상태라 문구를 구분해 둔다.
        assertTrue(thrown.getMessage().contains("externalRef"), thrown.getMessage());
    }

    @Test
    void 셀러_ID_0_은_없음이_아니라_0_번_셀러다() {
        // 0 을 "없음" 으로 취급하는 코드가 생기면 유도 실패한 조직들이 한 셀러로 뭉친다.
        // 여기서 0 이 통과하는 것이 정상이며, 부재는 오직 null 로만 표현된다.
        assertEquals(0L, scope(OrgType.SELLER, 0L).requireSellerId());
    }
}
