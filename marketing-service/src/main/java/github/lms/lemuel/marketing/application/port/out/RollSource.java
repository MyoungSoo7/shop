package github.lms.lemuel.marketing.application.port.out;

/**
 * 추첨 난수원 — {@code [0, 1)} 하나.
 *
 * <p>추첨 알고리즘({@code PrizeDraw})을 순수 함수로 두기 위해 난수를 포트로 뺐다. 레거시는
 * 서비스 메서드 안에서 직접 {@code new SecureRandom().nextDouble()} 을 불렀는데, 그러면
 * 분포가 설정한 확률과 맞는지 테스트할 방법이 없다. 테스트는 고정 난수를 넣고, 운영은
 * {@code SecureRandom} 을 재사용하는 어댑터를 쓴다 — 호출마다 새로 만들면 추첨이 몰릴 때
 * 엔트로피 초기화 비용이 그대로 응답 시간이 된다.
 */
public interface RollSource {
    double nextRoll();
}
