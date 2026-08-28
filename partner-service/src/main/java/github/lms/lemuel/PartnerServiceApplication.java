package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * partner-service 독립 부팅 진입점 — 입점 기업(파트너)이 "우리 몰에서 자기가 판 것" 을 보는 백오피스.
 *
 * <p>★ 자체 DB(lemuel_partner) 를 소유하는 DB-per-service 다. 형제 서비스와 같이 루트
 * {@code github.lms.lemuel} 에서 스캔해 partner 패키지 + shared-common(JWT·Outbox·멱등 인프라)
 * 빈만 잡는다. order/operation/marketing 은 의존(build.gradle.kts)에 없어 클래스패스에 존재하지
 * 않으므로 MSA 코드 경계가 유지된다.
 *
 * <p>루트 스캔을 좁히지 않는 것은 의도다 — {@code KafkaConsumerErrorHandlingConfig}(DLT 배선)와
 * {@code OutboxPublisherScheduler}(폴러)가 shared-common 에 있어서, 스캔을 partner 하위로 좁히면
 * 둘 다 빈이 만들어지지 않는다. 그러면 재시도 소진 메시지가 조용히 버려지고(guard KAFKA-DLQ)
 * outbox 행은 PENDING 인 채로 쌓인다(outbox-poller-gate). 둘 다 기동은 성공하므로 아무 신호도
 * 나지 않는다. 이 서비스는 발행이 0 이라 폴러가 빈 테이블만 훑지만, 그렇다고 빼면 위 두 개가
 * 같이 사라진다 — DLT 배선은 발행이 0 이어도 필요하다.
 *
 * <p><b>이 서비스는 원본을 하나도 갖지 않는다.</b> 주문·결제·상품·조직은 전부 다른 서비스의
 * 소유이고, 여기 있는 것은 그 이벤트로 쌓은 <i>읽기 전용 프로젝션</i>뿐이다. 그래서 쓰기 API 가
 * 없고 발행 토픽도 없다. 파트너가 "이 주문 취소해줘" 같은 걸 하려 들면 그건 이 서비스가 아니라
 * order-service 의 일이다 — 여기에 쓰기 경로를 내는 순간 주문 상태의 정본이 둘이 된다.
 *
 * <p>{@code @EnableScheduling} 을 여기 걸지 않는 것도 의도다 — 이 모듈엔 자체 스케줄러가 없고,
 * outbox 폴링에 필요한 스케줄링은 shared-common 의 {@code AsyncConfig} 가 이미 켠다(루트 스캔이
 * 그걸 잡는다). 형제 모듈이 붙여 둔 것은 자기 스케줄러가 있어서다.
 */
@SpringBootApplication
public class PartnerServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(PartnerServiceApplication.class, args);
    }
}
