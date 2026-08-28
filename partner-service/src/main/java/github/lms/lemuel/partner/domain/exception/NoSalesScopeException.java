package github.lms.lemuel.partner.domain.exception;

import github.lms.lemuel.partner.domain.OrgType;

/**
 * 조직은 있는데 매출을 볼 셀러 ID 가 없다.
 *
 * <p>두 경우다. CORPORATE 조직이면 원래 판매 주체가 아니라 매출이 없는 게 맞고, SELLER 인데
 * 없으면 {@code externalRef} 가 숫자가 아니어서 셀러 ID 를 유도하지 못한 것이다 —
 * 이쪽은 데이터 문제라 운영자가 봐야 한다. 메시지에 종류를 담아 둘을 구분한다.
 */
public class NoSalesScopeException extends RuntimeException {

    public NoSalesScopeException(long organizationId, OrgType orgType) {
        super(orgType == OrgType.CORPORATE
                ? "판매 조직이 아니어서 매출이 없습니다: organizationId=" + organizationId
                : "셀러 식별자를 확인할 수 없는 조직입니다(externalRef 형식): organizationId=" + organizationId);
    }
}
