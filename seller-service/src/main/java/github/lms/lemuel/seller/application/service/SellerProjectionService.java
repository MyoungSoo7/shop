package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.in.RecordCommerceUseCase;
import github.lms.lemuel.seller.application.port.in.RecordDirectoryUseCase;
import github.lms.lemuel.seller.application.port.out.ProductSubmissionPort;
import github.lms.lemuel.seller.application.port.out.SellerCommerceProjectionPort;
import github.lms.lemuel.seller.application.port.out.SellerDirectoryProjectionPort;
import github.lms.lemuel.seller.domain.OrgType;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SubmissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 아홉 개 토픽이 도착하는 곳 — 이 서비스의 <b>이벤트 쓰기 경로 전부</b>다.
 *
 * <p>원칙은 파트너 콘솔과 같다: <b>이벤트가 말한 것만 적고, 말하지 않은 것은 비워 둔다.</b>
 * 모르는 값을 기본값으로 채우면 화면이 거짓을 사실처럼 말하게 되고, 그 거짓은 나중에 진짜 값이
 * 도착해도 구분되지 않는다.
 *
 * <p>다만 여기에는 파트너에 없던 것이 하나 있다. {@link #productRegistered} 만은 사본이 아니라
 * <b>우리 원장(신청서)</b>을 건드린다 — 우리가 낸 요청의 회신이기 때문이다. 그래서 이 메서드만
 * 애그리거트를 불러 상태 전이를 시키고, 나머지 여덟은 여전히 upsert 한 줄이다.
 */
@Service
@Transactional
public class SellerProjectionService implements RecordDirectoryUseCase, RecordCommerceUseCase {

    private static final Logger log = LoggerFactory.getLogger(SellerProjectionService.class);

    private final SellerDirectoryProjectionPort directoryPort;
    private final SellerCommerceProjectionPort commercePort;
    private final ProductSubmissionPort submissionPort;

    public SellerProjectionService(SellerDirectoryProjectionPort directoryPort,
                                   SellerCommerceProjectionPort commercePort,
                                   ProductSubmissionPort submissionPort) {
        this.directoryPort = directoryPort;
        this.commercePort = commercePort;
        this.submissionPort = submissionPort;
    }

    // ------------------------------------------------------------------ 조직

    @Override
    public void organizationCreated(OrganizationCreated event) {
        Long sellerId = deriveSellerId(event);
        if (event.type() == OrgType.SELLER && sellerId == null) {
            // 판매 조직인데 셀러 ID 를 못 만들었다 = 그 조직은 상품 등록도 주문 조회도 못 한다.
            // 조용히 넘어가면 문의가 왔을 때 단서가 하나도 남지 않는다.
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
     * 0 이나 -1 로 메우면 유도에 실패한 조직들이 전부 같은 셀러로 뭉친다. 파트너 콘솔에서는 그게
     * 남의 매출을 보는 것이었고, 여기서는 <b>남의 이름으로 상품을 올리는 것</b>이 된다.
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

    // ------------------------------------------------------------------ 주문·결제

    @Override
    public void captured(SaleCaptured event) {
        commercePort.upsertSale(event.paymentId(), event.orderId(), event.sellerId(), event.amount(),
                event.paymentMethod(), event.capturedAt(), event.capturedAtEstimated());
    }

    @Override
    public void refunded(SaleRefunded event) {
        commercePort.upsertRefund(event.paymentId(), event.refundKey(), event.orderId(),
                event.refundAmount(), event.refundedTotal());
    }

    @Override
    public void orderCreated(OrderCreated event) {
        commercePort.upsertOrder(event.orderId(), event.userId(), event.productId(),
                event.status(), event.amount(), event.createdAt());
    }

    @Override
    public void productChanged(long productId, String name) {
        commercePort.upsertProduct(productId, name);
    }

    // ------------------------------------------------------------------ 회신

    @Override
    public void productRegistered(ProductRegistered event) {
        commercePort.linkProduct(event.productId(), event.name(), event.submissionId());

        Optional<ProductSubmission> found = submissionPort.loadAny(event.submissionId());
        if (found.isEmpty()) {
            // 우리 신청서가 아닌데 우리 앞으로 온 회신이다. 재처리 대상이 아니므로 예외로 올리지
            // 않는다 — 올리면 이 이벤트는 재시도를 다 쓰고 DLT 로 가서 사람 손을 부른다.
            log.warn("회신에 실린 신청서를 찾을 수 없습니다. submissionId={}, productId={}",
                    event.submissionId(), event.productId());
            return;
        }

        ProductSubmission submission = found.get();
        if (submission.productId() != null) {
            // 재전달. 같은 값이면 아무 일도 안 하는 게 맞고, 다른 값이면 사람이 봐야 한다 —
            // 한 신청서로 상품이 둘 생겼다는 뜻이기 때문이다.
            if (submission.productId().longValue() != event.productId()) {
                log.error("이미 다른 상품번호가 붙은 신청서에 회신이 왔습니다. "
                                + "submissionId={}, 기존={}, 도착={}",
                        event.submissionId(), submission.productId(), event.productId());
            }
            return;
        }
        if (submission.status() != SubmissionStatus.APPROVED) {
            // 승인 상태가 아닌 신청서에 상품번호를 붙이면 화면이 앞뒤가 안 맞는다. 여기서
            // catalogRegistered() 를 부르면 예외가 나고 DLT 로 갈 텐데, 이건 재시도로 풀릴 일이
            // 아니다. 사실만 남기고 넘어간다.
            log.error("승인 상태가 아닌 신청서에 카탈로그 회신이 왔습니다. submissionId={}, status={}",
                    event.submissionId(), submission.status());
            return;
        }
        submissionPort.save(submission.catalogRegistered(event.productId()));
    }
}
