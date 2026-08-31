package github.lms.lemuel.seller.domain.exception;

/**
 * 로그인은 됐는데 어느 입점 조직에도 속해 있지 않다.
 *
 * <p>일반 회원이 셀러 백오피스 URL 을 직접 열었을 때가 대부분이고, 그건 정상이다. 다만 방금
 * 초대된 사람도 잠깐 여기에 걸린다 — {@code organization.member_joined} 가 아직 도착하지 않은
 * 짧은 창이다. 화면 문구가 "권한 없음" 으로 끝나면 그 사람은 초대가 잘못된 줄 알고 다시
 * 요청한다. "초대 직후라면 잠시 뒤 다시" 를 같이 말해야 한다.
 */
public class SellerScopeNotFoundException extends RuntimeException {

    public SellerScopeNotFoundException(long userId) {
        super("입점 조직에 속해 있지 않은 사용자입니다: userId=" + userId);
    }
}
