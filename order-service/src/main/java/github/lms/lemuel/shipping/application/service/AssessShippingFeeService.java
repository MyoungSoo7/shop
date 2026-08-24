package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.AssessShippingFeeUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadProductShippingChargePort;
import github.lms.lemuel.shipping.application.port.out.LoadProductShippingChargePort.ProductShippingCharge;
import github.lms.lemuel.shipping.application.port.out.LoadSellerShippingPolicyPort;
import github.lms.lemuel.shipping.domain.SellerShippingPolicy;
import github.lms.lemuel.shipping.domain.ShippingFeeAssessment;
import github.lms.lemuel.shipping.domain.ShippingFeeCalculator;
import github.lms.lemuel.shipping.domain.ShippingLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 배송비 산정 서비스 — 상품 마스터에서 셀러·부과 유형을 해석해 도메인 계산기에 넘긴다.
 *
 * <p>조회는 두 번뿐이다: 라인의 상품들을 한 번에(N+1 방지), 거기서 나온 셀러들을 한 번에.
 * 계산 자체는 {@link ShippingFeeCalculator} 에 있고 이 서비스는 조회·조립만 한다.
 *
 * <p><b>부과하지 않는 경우</b> — 상품 마스터에 없는 상품, 셀러가 붙지 않은 상품(seller_id NULL)은
 * 라인에서 조용히 빠진다. 배송비는 고객에게 청구되는 돈이라, 근거 데이터가 없을 때의 안전한 착지는
 * "0 원"이지 "추정 부과"가 아니다.
 */
@Service
@Transactional(readOnly = true)
public class AssessShippingFeeService implements AssessShippingFeeUseCase {

    private static final Logger log = LoggerFactory.getLogger(AssessShippingFeeService.class);

    private final LoadProductShippingChargePort loadProductShippingChargePort;
    private final LoadSellerShippingPolicyPort loadSellerShippingPolicyPort;

    public AssessShippingFeeService(LoadProductShippingChargePort loadProductShippingChargePort,
                                    LoadSellerShippingPolicyPort loadSellerShippingPolicyPort) {
        this.loadProductShippingChargePort = loadProductShippingChargePort;
        this.loadSellerShippingPolicyPort = loadSellerShippingPolicyPort;
    }

    @Override
    public ShippingFeeAssessment assess(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return ShippingFeeAssessment.none();
        }

        Set<Long> productIds = new LinkedHashSet<>();
        for (OrderLine line : lines) {
            productIds.add(line.productId());
        }

        Map<Long, ProductShippingCharge> charges = loadProductShippingChargePort.loadByProductIds(productIds);

        List<ShippingLine> shippingLines = new ArrayList<>(lines.size());
        Set<Long> sellerIds = new LinkedHashSet<>();
        for (OrderLine line : lines) {
            ProductShippingCharge charge = charges.get(line.productId());
            if (charge == null || charge.sellerId() == null) {
                log.debug("배송비 부과 제외 — 상품 배송비 속성 미상: productId={}", line.productId());
                continue;
            }
            shippingLines.add(ShippingLine.of(charge.sellerId(), charge.chargeType(),
                    charge.individualFee(), line.lineAmount()));
            sellerIds.add(charge.sellerId());
        }

        if (shippingLines.isEmpty()) {
            return ShippingFeeAssessment.none();
        }

        Map<Long, SellerShippingPolicy> policies = loadSellerShippingPolicyPort.loadBySellerIds(sellerIds);
        return ShippingFeeCalculator.assess(shippingLines, policies);
    }
}
