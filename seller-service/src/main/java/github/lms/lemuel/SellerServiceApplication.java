package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * seller-service 독립 부팅 진입점 — 입점 셀러가 <b>자기 상품을 직접 등록하고</b> 그 상품이 주문된
 * 것을 관리하는 백오피스(레퍼런스: ssgb2e-outbackoffice).
 *
 * <p>★ 자체 DB(lemuel_seller) 를 소유하는 DB-per-service 다. 형제 서비스와 같이 루트
 * {@code github.lms.lemuel} 에서 스캔해 seller 패키지 + shared-common(JWT·Outbox·멱등 인프라)
 * 빈만 잡는다. order/operation/marketing/partner 는 의존(build.gradle.kts)에 없어 클래스패스에
 * 존재하지 않으므로 MSA 코드 경계가 유지된다.
 *
 * <p>루트 스캔을 좁히지 않는 것은 의도다 — {@code KafkaConsumerErrorHandlingConfig}(DLT 배선)와
 * {@code OutboxPublisherScheduler}(폴러)가 shared-common 에 있어서, 스캔을 seller 하위로 좁히면
 * 둘 다 빈이 만들어지지 않는다. 그러면 재시도 소진 메시지가 조용히 버려지고(guard KAFKA-DLQ)
 * outbox 행은 PENDING 인 채로 쌓인다(outbox-poller-gate). 둘 다 기동은 성공하므로 아무 신호도
 * 나지 않는다. 파트너 콘솔과 달리 이 서비스는 발행이 <b>실제로 있으므로</b> 폴러가 죽으면
 * 승인한 상품이 영영 카탈로그에 실리지 않는다.
 *
 * <h2>파트너 콘솔과 무엇이 다른가</h2>
 * partner-service 는 원본을 하나도 갖지 않는 순수 읽기 프로젝션이었다. 이 서비스는 <b>원본을
 * 하나 갖는다</b> — 상품 등록 신청서({@code product_submissions})다. 그 하나를 갖는 대가로
 * 발행 토픽 둘({@code seller.product_approved}, {@code seller.shipment_registered})이 생겼다.
 *
 * <p>다만 <b>상품과 주문의 원장은 여전히 order-service 소유</b>다. 승인해도 여기서 카탈로그에
 * 직접 쓰지 않고, 송장도 여기서 배송 상태를 바꾸지 않는다. 둘 다 이벤트로 요청하고 회신을 기다린다.
 * 여기서 직접 쓰면 DB-per-service 는 이름만 남고 상품·배송 상태의 정본이 둘이 된다.
 *
 * <p>{@code @EnableScheduling} 을 여기 걸지 않는 것도 의도다 — 이 모듈엔 자체 스케줄러가 없고,
 * outbox 폴링에 필요한 스케줄링은 shared-common 의 {@code AsyncConfig} 가 이미 켠다(루트 스캔이
 * 그걸 잡는다). 형제 모듈이 붙여 둔 것은 자기 스케줄러가 있어서다.
 */
@SpringBootApplication
public class SellerServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(SellerServiceApplication.class, args);
    }
}
