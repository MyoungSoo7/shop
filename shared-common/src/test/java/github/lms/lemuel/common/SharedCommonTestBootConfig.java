package github.lms.lemuel.common;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * shared-common 은 라이브러리 모듈이라 {@code @SpringBootApplication} 진입점이 없다.
 * {@code @DataJpaTest} 등 슬라이스 테스트는 패키지를 위로 탐색해 {@code @SpringBootConfiguration}
 * 을 찾으므로, 테스트 전용 부트 설정을 {@code common} 패키지 루트에 둬 하위 audit/outbox 등
 * JpaEntity·Repository 가 컴포넌트/엔티티 스캔에 잡히게 한다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class SharedCommonTestBootConfig {
}
