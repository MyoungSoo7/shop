package github.lms.lemuel.partner.application.service;

import github.lms.lemuel.partner.application.port.in.RecordCatalogUseCase;
import github.lms.lemuel.partner.application.port.in.RecordDirectoryUseCase;
import github.lms.lemuel.partner.application.port.in.RecordSalesUseCase;
import github.lms.lemuel.partner.application.port.out.PartnerCatalogProjectionPort;
import github.lms.lemuel.partner.application.port.out.PartnerDirectoryProjectionPort;
import github.lms.lemuel.partner.application.port.out.PartnerSalesProjectionPort;
import github.lms.lemuel.partner.domain.OrgType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아홉 개 토픽이 도착하는 곳 — 이 서비스의 <b>쓰기 경로 전부</b>다.
 *
 * <p>사용자 요청으로는 어떤 테이블에도 쓰지 않는다. 그래서 여기에 검증·권한 검사가 없다.
 * 대신 원칙이 하나 있다: <b>이벤트가 말한 것만 적고, 말하지 않은 것은 비워 둔다.</b>
 * 모르는 값을 기본값으로 채우면 화면이 거짓을 사실처럼 말하게 되고, 그 거짓은 나중에
 * 진짜 값이 도착해도 구분되지 않는다.
 *
 * <p>세 유스케이스를 한 클래스에 담은 것은, 셋 다 "이벤트 → upsert" 한 줄짜리라 나누면
 * 파일만 늘고 규칙은 흩어지기 때문이다. 규칙이 자라면(예: 등급별 처리) 그때 나눈다.
 */
@Service
@Transactional
public class PartnerProjectionService
        implements RecordDirectoryUseCase, RecordSalesUseCase, RecordCatalogUseCase {

    private static final Logger log = LoggerFactory.getLogger(PartnerProjectionService.class);

    private final PartnerDirectoryProjectionPort directoryPort;
    private final PartnerSalesProjectionPort salesPort;
    private final PartnerCatalogProjectionPort catalogPort;

    public PartnerProjectionService(PartnerDirectoryProjectionPort directoryPort,
                                    PartnerSalesProjectionPort salesPort,
                                    PartnerCatalogProjectionPort catalogPort) {
        this.directoryPort = directoryPort;
        this.salesPort = salesPort;
        this.catalogPort = catalogPort;
    }

    // ------------------------------------------------------------------ 조직

    @Override
    public void organizationCreated(OrganizationCreated event) {
        Long sellerId = deriveSellerId(event);
        if (event.type() == OrgType.SELLER && sellerId == null) {
            // 판매 조직인데 셀러 ID 를 못 만들었다 = 그 조직의 콘솔은 매출이 영영 비어 있다.
            // 조용히 넘어가면 "왜 매출이 안 보이냐" 는 문의가 왔을 때 단서가 하나도 없다.
            log.warn("SELLER 조직인데 externalRef 에서 셀러 ID 를 유도하지 못했습니다. "
                    + "organizationId={}, externalRef={}", event.organizationId(), event.externalRef());
        }
        directoryPort.upsertOrganization(event.organizationId(), event.name(), event.type(),
                event.externalRef(), sellerId, event.ownerUserId());
    }

    /**
     * {@code externalRef} → 셀러 ID.
     *
     * <p>SELLER 만 대상이고, 숫자가 아니면 null 이다. 샘플이 {@code "SELLER-777"} 처럼 접두사를
     * 달고 오므로 마지막 하이픈 뒤 숫자도 받아 준다. <b>그래도 안 되면 비워 둔다</b> —
     * 0 이나 -1 로 메우면 유도에 실패한 조직들이 전부 같은 셀러로 뭉쳐, 서로의 매출을 본다.
     * 매출 조회가 {@code seller_id} 하나만 믿기 때문에 이 한 줄이 곧 격리 경계다.
     */
    private static Long deriveSellerId(OrganizationCreated event) {
        if (event.type() != OrgType.SELLER || event.externalRef() == null) {
            return null;
        }
        String ref = event.externalRef().trim();
        String tail = ref.substring(ref.lastIndexOf('-') + 1);
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }

    @Override
    public void memberJoined(MemberJoined event) {
        directoryPort.upsertMembership(event.membershipId(), event.organizationId(),
                event.userId(), event.role());
    }

    @Override
    public void memberRemoved(MemberRemoved event) {
        directoryPort.markRemoved(event.membershipId());
    }

    @Override
    public void memberRoleChanged(MemberRoleChanged event) {
        directoryPort.changeRole(event.membershipId(), event.newRole());
    }

    // ------------------------------------------------------------------ 매출

    @Override
    public void captured(SaleCaptured event) {
        salesPort.upsertSale(event.paymentId(), event.orderId(), event.sellerId(), event.amount(),
                event.sellerTier(), event.settlementCycle(), event.paymentMethod(),
                event.capturedAt(), event.capturedAtEstimated());
    }

    @Override
    public void refunded(SaleRefunded event) {
        salesPort.upsertRefund(event.paymentId(), event.refundKey(), event.orderId(),
                event.refundAmount(), event.refundedTotal());
    }

    // ------------------------------------------------------------------ 보조

    @Override
    public void orderCreated(OrderCreated event) {
        catalogPort.upsertOrder(event.orderId(), event.userId(), event.productId(),
                event.status(), event.amount(), event.createdAt());
    }

    @Override
    public void productChanged(long productId, String name) {
        catalogPort.upsertProduct(productId, name);
    }

    @Override
    public void sellerTierChanged(SellerTierChanged event) {
        if (event.backfill()) {
            // 백필은 "이때 바뀌었다" 가 아니라 "이미 이랬다" 다. 등급 값은 반영하되 로그로 구분해
            // 둔다 — 등급 변경 시각을 백필 시각으로 읽는 분석이 나중에 반드시 나온다.
            log.info("셀러 등급 백필 반영. sellerId={}, tier={}, effectiveFrom={}",
                    event.sellerId(), event.newTier(), event.effectiveFrom());
        }
        catalogPort.upsertSellerTier(event.sellerId(), event.newTier(), event.reason(),
                event.effectiveFrom(), event.occurredAt());
    }
}
