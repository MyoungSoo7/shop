package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.ManageSellerShippingPolicyUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadSellerShippingPolicyPort;
import github.lms.lemuel.shipping.application.port.out.SaveSellerShippingPolicyPort;
import github.lms.lemuel.shipping.application.port.out.SellerExistsPort;
import github.lms.lemuel.shipping.domain.SellerShippingPolicy;
import github.lms.lemuel.shipping.domain.exception.SellerNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ManageSellerShippingPolicyService implements ManageSellerShippingPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManageSellerShippingPolicyService.class);

    private final LoadSellerShippingPolicyPort loadPort;
    private final SaveSellerShippingPolicyPort savePort;
    private final SellerExistsPort sellerExistsPort;

    public ManageSellerShippingPolicyService(LoadSellerShippingPolicyPort loadPort,
                                             SaveSellerShippingPolicyPort savePort,
                                             SellerExistsPort sellerExistsPort) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.sellerExistsPort = sellerExistsPort;
    }

    @Override
    public SellerShippingPolicy upsert(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold) {
        // 값 검증은 도메인 팩토리가 한다 — 음수 배송비·음수 임계는 여기 도달 전에 거절된다.
        SellerShippingPolicy policy = SellerShippingPolicy.of(sellerId, baseFee, freeThreshold);
        // 대상 존재 확인은 도메인 불변식이 아니라 다른 애그리거트 사실이라 여기서 묻는다.
        // DB FK 도 같은 것을 막지만 그 경로는 500 이 된다 — 오타를 장애로 보이게 하지 않는다.
        if (!sellerExistsPort.existsById(sellerId)) {
            throw new SellerNotFoundException(sellerId);
        }
        SellerShippingPolicy saved = savePort.save(policy);
        log.info("셀러 배송비 정책 저장: sellerId={}, baseFee={}, freeThreshold={}",
                sellerId, baseFee, freeThreshold);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SellerShippingPolicy> find(Long sellerId) {
        return loadPort.loadBySellerId(sellerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SellerShippingPolicy> findAll() {
        return loadPort.loadAll();
    }
}
