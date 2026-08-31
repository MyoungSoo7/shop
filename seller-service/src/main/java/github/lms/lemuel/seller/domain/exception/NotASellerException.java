package github.lms.lemuel.seller.domain.exception;

import github.lms.lemuel.seller.domain.OrgType;

/**
 * 조직은 있는데 셀러 ID 가 없다 — 이 백오피스로 할 수 있는 일이 하나도 없다는 뜻이다.
 *
 * <p>두 경우이고, 사용자가 해야 할 일이 정반대다.
 * <ul>
 *   <li>CORPORATE 조직 — 사는 쪽이지 파는 쪽이 아니다. 정상이고, 이 화면을 쓸 일이 없다.</li>
 *   <li>SELLER 인데 없음 — {@code externalRef} 가 숫자가 아니어서 셀러 ID 를 유도하지 못했다.
 *       데이터 문제라 사용자는 아무것도 할 수 없고 운영자만 고칠 수 있다.</li>
 * </ul>
 *
 * <p>메시지에 종류를 담아 둘을 구분한다. 하나로 뭉뚱그리면 두 번째 경우가 영영 신고되지 않는다 —
 * 셀러는 "아직 입점 처리가 안 됐나 보다" 하고 기다리기 때문이다.
 */
public class NotASellerException extends RuntimeException {

    public NotASellerException(long organizationId, OrgType orgType) {
        super(orgType == OrgType.CORPORATE
                ? "판매 조직이 아니어서 셀러 백오피스를 쓸 수 없습니다: organizationId=" + organizationId
                : "셀러 식별자를 확인할 수 없는 조직입니다(externalRef 형식): organizationId=" + organizationId);
    }
}
