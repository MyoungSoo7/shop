package github.lms.lemuel.sellertier.config;

import github.lms.lemuel.sellertier.application.port.out.LoadSellerNetSalesPort;
import github.lms.lemuel.sellertier.application.port.out.LoadTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.PublishSellerTierEventPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierHistoryPort;
import github.lms.lemuel.sellertier.application.port.out.LoadTierCacheDriftPort;
import github.lms.lemuel.sellertier.application.service.CheckSellerTierIntegrityService;
import github.lms.lemuel.sellertier.application.service.EvaluateSellerTiersService;
import github.lms.lemuel.sellertier.application.service.OverrideSellerTierService;
import github.lms.lemuel.sellertier.application.service.SellerTierChangeProcessor;
import github.lms.lemuel.sellertier.domain.SellerTierPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 등급 정책 배선 (ADR 0031).
 *
 * <p><b>임계는 설정에서 온다.</b> 등급 승급은 곧 수수료 인하라 임계값은 재무 승인 사항이고, 코드에
 * 박으면 승인 때마다 배포가 필요해진다. 기본값은 사실상 아무도 승급하지 못하는 크기로 둬서,
 * <b>값을 명시적으로 넣기 전에는 자동 승급이 일어나지 않게</b> 한다 — 미승인 상태의 조용한 승급이
 * 수수료 수입을 깎는 사고를 막는다.
 *
 * <p>임계가 성립하지 않으면({@code SellerTierPolicy.of} 검증 실패) 애플리케이션이 기동하지 않는다.
 * 잘못된 정책으로 배치가 도는 것보다 뜨지 않는 편이 안전하다.
 */
@Configuration
public class SellerTierConfig {

    @Bean
    public SellerTierPolicy sellerTierPolicy(
            @Value("${app.seller-tier.vip-threshold:999999999999}") BigDecimal vipThreshold,
            @Value("${app.seller-tier.strategic-threshold:9999999999999}") BigDecimal strategicThreshold) {
        return SellerTierPolicy.of(vipThreshold, strategicThreshold);
    }

    @Bean
    public EvaluateSellerTiersService evaluateSellerTiersService(
            LoadSellerNetSalesPort netSalesPort,
            LoadTierAssignmentPort loadPort,
            SellerTierChangeProcessor processor,
            SellerTierPolicy policy,
            @Value("${app.seller-tier.miss-threshold:2}") int missThreshold) {
        return new EvaluateSellerTiersService(netSalesPort, loadPort, processor, policy, missThreshold);
    }

    @Bean
    public CheckSellerTierIntegrityService checkSellerTierIntegrityService(LoadTierCacheDriftPort driftPort) {
        return new CheckSellerTierIntegrityService(driftPort);
    }

    /**
     * 관리자 지정도 <b>같은 유예 값</b>을 쓴다 — 지정과 자동 판정이 서로 다른 보호 기간을 쓰면
     * "왜 이 셀러만 다음 달에 내려갔나"를 설명할 수 없다.
     */
    @Bean
    public OverrideSellerTierService overrideSellerTierService(
            LoadTierAssignmentPort loadPort,
            SaveTierAssignmentPort savePort,
            SaveTierHistoryPort historyPort,
            PublishSellerTierEventPort eventPort,
            @Value("${app.seller-tier.guard-months:3}") int guardMonths) {
        return new OverrideSellerTierService(loadPort, savePort, historyPort, eventPort, guardMonths);
    }
}
