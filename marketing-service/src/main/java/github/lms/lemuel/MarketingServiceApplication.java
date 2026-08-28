package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * marketing-service 독립 부팅 진입점 — 이벤트 프로모션(출석체크·럭키박스) 운영 도구.
 *
 * <p>★ 자체 DB(lemuel_marketing) 를 소유하는 DB-per-service 다. operation-service 와 같이
 * 루트 {@code github.lms.lemuel} 에서 스캔해 marketing 패키지 + shared-common(JWT·Outbox·멱등
 * 인프라) 빈만 잡는다. order/operation 은 의존(build.gradle.kts)에 없어 클래스패스에 존재하지
 * 않으므로 MSA 코드 경계가 유지된다.
 *
 * <p>루트 스캔을 좁히지 않는 것은 의도다 — {@code KafkaConsumerErrorHandlingConfig}(DLT 배선)와
 * {@code OutboxPublisherScheduler}(폴러)가 shared-common 에 있어서, 스캔을 marketing 하위로
 * 좁히면 둘 다 빈이 만들어지지 않는다. 그러면 재시도 소진 메시지가 조용히 버려지고(guard
 * KAFKA-DLQ) outbox 행은 PENDING 인 채로 쌓인다(outbox-poller-gate). 둘 다 기동은 성공하므로
 * 아무 신호도 나지 않는다.
 *
 * <p><b>보상은 이 서비스가 지급하지 않는다.</b> 출석/추첨이 확정되면 outbox 로
 * {@code lemuel.marketing.reward_requested} 를 내고, 실제 적립은 order-service 의 포인트
 * 원장이 한다. 원장을 여기에 복제하면 잔액이 두 곳에 생긴다 — 그건 정합성 경계를 나누는 게
 * 아니라 깨는 것이다.
 */
@SpringBootApplication
@EnableScheduling
public class MarketingServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(MarketingServiceApplication.class, args);
    }
}
