package github.lms.lemuel.partner.domain.exception;

/**
 * 로그인은 됐는데 어느 파트너 조직에도 속해 있지 않다.
 *
 * <p>일반 회원이 파트너 콘솔 URL 을 직접 열었을 때가 대부분이고, 정상이다. 다만 방금 초대된
 * 사람이 이걸 만나는 경우도 있다 — {@code organization.member_joined} 가 아직 도착하지 않은
 * 짧은 창이다. 그래서 화면 문구는 "권한 없음" 이 아니라 "아직 반영되지 않았을 수 있다" 를 함께
 * 말해야 한다.
 */
public class PartnerScopeNotFoundException extends RuntimeException {

    public PartnerScopeNotFoundException(long userId) {
        super("파트너 조직에 속해 있지 않은 사용자입니다: userId=" + userId);
    }
}
