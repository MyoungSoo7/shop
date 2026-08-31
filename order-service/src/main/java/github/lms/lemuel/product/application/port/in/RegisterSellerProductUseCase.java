package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.math.BigDecimal;

/**
 * 승인된 셀러 신청서를 카탈로그에 싣는다 — seller-service 요청의 <b>처리</b>.
 *
 * <p>컨슈머가 {@link CreateProductUseCase}·{@link UpdateProductUseCase} 를 직접 조합하지 않고
 * 이 유스케이스를 거치는 이유는 <b>등재와 회신이 한 트랜잭션이어야</b> 해서다. 상품은 생겼는데
 * 회신이 유실되면 셀러 콘솔의 그 신청서는 영원히 "등록 처리 중" 에 남고, 셀러는 같은 상품을
 * 한 번 더 올린다. 조합을 어댑터에 두면 그 원자성이 어댑터의 사정이 되고, 다음에 다른 어댑터가
 * 같은 일을 할 때 규칙이 복사된다.
 */
public interface RegisterSellerProductUseCase {

    /**
     * @return 등재된(또는 갱신된) 상품. 회신 발행은 이 호출 안에서 함께 일어난다.
     */
    Product register(SellerProductApproval approval);

    /**
     * {@code baseProductId} 가 이 명령의 유일한 분기다 — null 이면 신규 등록, 있으면 그 상품의 수정.
     *
     * <p>seller-service 의 {@code SubmissionType} 을 그대로 들여오지 않는다. 다른 서비스의 enum 을
     * 타입으로 받으면 그 순간 두 서비스가 코드로 묶이고, 저쪽이 값을 하나 늘리면 이쪽이 컴파일
     * 단계에서 부서진다. 문자열→분기 해석은 이벤트를 받는 어댑터의 일이다.
     */
    record SellerProductApproval(
            long submissionId,
            long sellerId,
            Long baseProductId,
            String name,
            String description,
            BigDecimal price,
            Integer stockQuantity
    ) {
        public SellerProductApproval {
            if (submissionId <= 0) {
                throw new ProductInvariantViolationException("submissionId must be positive");
            }
            if (sellerId <= 0) {
                throw new ProductInvariantViolationException("sellerId must be positive");
            }
        }
    }
}
