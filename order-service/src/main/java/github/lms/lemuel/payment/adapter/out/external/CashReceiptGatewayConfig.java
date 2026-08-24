package github.lms.lemuel.payment.adapter.out.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 실 현금영수증 연동 배선 — <b>운영 프로파일 + 연동 ON</b> 일 때만 뜬다.
 *
 * <p>두 빈({@link CashReceiptApiClient}, {@link LiveCashReceiptGatewayAdapter})의 조건을 한 곳에
 * 모은 이유: 같은 조건을 두 클래스에 각각 애노테이션으로 붙이면 한쪽만 고쳤을 때 조건이
 * 어긋난다 — 어댑터는 뜨는데 클라이언트가 없어 기동이 깨지거나, 반대로 포트 구현이 둘이 되어
 * 주입이 모호해진다.
 *
 * <p>자격증명은 기본값을 빈 문자열로 두고 {@code CashReceiptApiClient} 생성자가 검증한다.
 * {@code ${VAR}} 를 기본값 없이 두는 방식은 <b>연동을 쓰지 않는 개발 환경까지</b> 기동을 막는다.
 * 여기서는 "켠 사람만 자격증명을 대라"가 맞다 — 켰는데 비어 있으면 그 자리에서 기동이 실패한다.
 */
@Configuration
@Profile("prod")
@ConditionalOnProperty(name = "app.cash-receipt.enabled", havingValue = "true")
public class CashReceiptGatewayConfig {

    @Bean
    public CashReceiptApiClient cashReceiptApiClient(
            @Value("${app.cash-receipt.base-url:}") String baseUrl,
            @Value("${app.cash-receipt.issue-path:}") String issuePath,
            @Value("${app.cash-receipt.cancel-path:}") String cancelPath,
            @Value("${app.cash-receipt.merchant-id:}") String merchantId,
            @Value("${app.cash-receipt.secret-key:}") String secretKey) {
        return new CashReceiptApiClient(baseUrl, issuePath, cancelPath, merchantId, secretKey);
    }

    @Bean
    public LiveCashReceiptGatewayAdapter liveCashReceiptGatewayAdapter(CashReceiptApiClient apiClient) {
        return new LiveCashReceiptGatewayAdapter(apiClient);
    }
}
