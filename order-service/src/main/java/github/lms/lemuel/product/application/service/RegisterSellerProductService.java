package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.CreateProductUseCase;
import github.lms.lemuel.product.application.port.in.RegisterSellerProductUseCase;
import github.lms.lemuel.product.application.port.in.UpdateProductUseCase;
import github.lms.lemuel.product.application.port.out.PublishProductEventPort;
import github.lms.lemuel.product.domain.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인된 신청서 → 카탈로그 등재 → 회신 발행. 셋이 한 트랜잭션이다.
 *
 * <h2>재고를 덮어쓰지 않는다</h2>
 * 수정 신청서에도 재고가 실려 오지만 여기서는 쓰지 않는다. 그 숫자는 셀러가 <b>신청서를 쓴
 * 시점</b>의 값이고, 심사가 끝나는 사이에 그 상품은 팔린다. 그대로 덮으면 이미 나간 주문만큼의
 * 재고가 되살아나 없는 물건이 팔린다. 재고는 주문·입고 경로가 증분으로 움직이는 값이지
 * 스냅샷으로 맞추는 값이 아니다. (신규 등록에서는 신청서의 재고가 곧 최초 재고다 — 그때는
 * 되살릴 과거가 없다.)
 *
 * <h2>왜 create/update 를 다시 부르는가</h2>
 * 상품명 중복 검사·도메인 불변식·캐시 무효화가 전부 그 두 서비스에 있다. 여기서 저장 포트를
 * 직접 부르면 셀러가 올린 상품만 그 규칙들을 우회하게 되고, 우회한다는 사실은 어디에도 안 적힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RegisterSellerProductService implements RegisterSellerProductUseCase {

    private final CreateProductUseCase createProductUseCase;
    private final UpdateProductUseCase updateProductUseCase;
    private final PublishProductEventPort publishProductEventPort;

    @Override
    public Product register(SellerProductApproval approval) {
        Product product = approval.baseProductId() == null
                ? create(approval)
                : update(approval);

        // 회신이 마지막이다. 등재가 실패하면 outbox 행도 함께 롤백되어 회신이 나가지 않는다 —
        // 셀러 콘솔은 "등록 처리 중" 에 머물고, 그 상태는 사람이 볼 수 있다. 반대로 회신을 먼저
        // 쓰면 등재가 실패해도 신청서만 "완료" 로 바뀌어 아무도 문제를 모른다.
        publishProductEventPort.publishSellerProductRegistered(
                product.getId(), product.getName(), approval.submissionId(), approval.sellerId());

        log.info("셀러 상품 등재 완료: productId={}, submissionId={}, sellerId={}",
                product.getId(), approval.submissionId(), approval.sellerId());
        return product;
    }

    private Product create(SellerProductApproval approval) {
        return createProductUseCase.createProduct(new CreateProductUseCase.CreateProductCommand(
                approval.name(),
                approval.description(),
                approval.price(),
                approval.stockQuantity()));
    }

    /**
     * 수정은 이름·설명과 가격 두 번으로 나뉜다 — order-service 의 유스케이스가 그렇게 갈려 있다.
     *
     * <p>둘 다 같은 트랜잭션 안이므로 중간에 실패하면 앞의 변경도 함께 되돌아간다. 이름만 바뀌고
     * 가격은 옛날 값인 상태로 커밋되는 경로는 없다.
     */
    private Product update(SellerProductApproval approval) {
        long productId = approval.baseProductId();
        updateProductUseCase.updateProductInfo(new UpdateProductUseCase.UpdateProductInfoCommand(
                productId, approval.name(), approval.description()));
        return updateProductUseCase.updateProductPrice(new UpdateProductUseCase.UpdateProductPriceCommand(
                productId, approval.price()));
    }
}
