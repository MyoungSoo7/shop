package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductImage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadProductImagePort {

    Optional<ProductImage> findByIdNotDeleted(Long imageId);

    List<ProductImage> findByProductIdNotDeleted(Long productId);

    Optional<ProductImage> findPrimaryImageByProductId(Long productId);

    /**
     * 여러 상품의 대표 이미지를 한 번에. 대표 이미지가 없는 상품은 결과 맵에 키가 없다.
     *
     * <p>목록 화면을 위한 것이다 — 항목마다 {@link #findPrimaryImageByProductId} 를 부르면
     * 상품 조회와 별개로 항목 수만큼 쿼리가 더 나간다.
     */
    Map<Long, ProductImage> findPrimaryImagesByProductIds(Collection<Long> productIds);

    long countByProductIdNotDeleted(Long productId);
}
