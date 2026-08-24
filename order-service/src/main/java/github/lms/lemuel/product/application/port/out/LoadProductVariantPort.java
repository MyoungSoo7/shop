package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductVariant;

import java.util.List;
import java.util.Optional;

public interface LoadProductVariantPort {

    Optional<ProductVariant> loadById(Long id);

    Optional<ProductVariant> loadBySku(String sku);

    List<ProductVariant> loadByProductId(Long productId);

    /**
     * 조합 서명으로 SKU 단건 조회. {@code (product_id, option_signature)} 유니크 인덱스를 타므로
     * 옵션 조합 해석이 전량 스캔 + 문자열 비교에서 인덱스 단건 조회로 바뀐다.
     */
    Optional<ProductVariant> loadByOptionSignature(Long productId, String optionSignature);

    /**
     * SKU 를 하나라도 가진 상품 id 목록(오름차순). 옵션 카탈로그 백필이 대상 상품을 훑을 때만 쓴다 —
     * 전체 SKU 를 한 번에 메모리에 올리지 않기 위해 상품 단위로 나눠 처리한다.
     */
    List<Long> findProductIdsWithVariants();
}
