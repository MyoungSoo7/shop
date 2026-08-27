package github.lms.lemuel.wishlist.adapter.out.product;

import github.lms.lemuel.product.application.port.out.LoadProductImagePort;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductImage;
import github.lms.lemuel.product.domain.ProductStatus;
import github.lms.lemuel.wishlist.application.port.out.LoadWishlistProductPort;
import github.lms.lemuel.wishlist.domain.WishlistAvailability;
import github.lms.lemuel.wishlist.domain.WishlistProduct;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 찜 ↔ 상품 사이의 부패 방지 계층.
 *
 * <p>상품 슬라이스의 개념({@link ProductStatus}, 재고 수량)을 찜이 쓰는 개념
 * ({@link WishlistAvailability})으로 <b>여기서만</b> 번역한다. 찜의 도메인·서비스는 상품 상태를
 * 모르며, 상품이 상태를 하나 더 늘려도 고칠 곳은 이 파일뿐이다.
 *
 * <p>상품의 JPA 엔티티·리포지토리가 아니라 <b>상품 슬라이스의 아웃바운드 포트</b>를 통해 읽는다 —
 * 어댑터가 타 도메인의 {@code adapter.out.persistence} 를 직접 참조하지 않는다는 이 저장소의
 * 아키텍처 규칙({@code HexagonalArchitectureTest}) 그대로다.
 */
@Component
public class WishlistProductQueryAdapter implements LoadWishlistProductPort {

    private final LoadProductPort loadProductPort;
    private final LoadProductImagePort loadProductImagePort;

    public WishlistProductQueryAdapter(LoadProductPort loadProductPort,
                                       LoadProductImagePort loadProductImagePort) {
        this.loadProductPort = loadProductPort;
        this.loadProductImagePort = loadProductImagePort;
    }

    @Override
    public Map<Long, WishlistProduct> findAllByIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<Product> products = loadProductPort.findAllByIds(productIds);
        // 이미지도 한 번에. 상품마다 부르면 목록 한 장이 곧 N+1 이고, 그게 이 포트가 존재하는 이유다.
        Map<Long, ProductImage> primaryImages =
                loadProductImagePort.findPrimaryImagesByProductIds(productIds);

        Map<Long, WishlistProduct> result = new LinkedHashMap<>();
        for (Product product : products) {
            ProductImage image = primaryImages.get(product.getId());
            result.put(product.getId(), new WishlistProduct(
                    product.getId(),
                    product.getName(),
                    product.getPrice(),
                    availabilityOf(product),
                    image == null ? null : image.getUrl()));
        }
        // 조회되지 않은 id 는 여기 없다 — 삭제된 상품을 지어내지 않는다. 서비스가 번역한다.
        return result;
    }

    /**
     * 상품 상태 + 재고 → 찜이 보여 줄 사유.
     *
     * <p>단종을 품절보다 먼저 본다. 단종된 상품의 재고가 0 인 것은 흔한 일인데, 순서를 뒤집으면
     * 영영 안 나올 물건이 "품절"로 보이고 사용자는 재입고를 기다리게 된다.
     */
    private static WishlistAvailability availabilityOf(Product product) {
        ProductStatus status = product.getStatus();
        if (status == ProductStatus.DISCONTINUED) {
            return WishlistAvailability.DISCONTINUED;
        }
        if (status == ProductStatus.INACTIVE) {
            return WishlistAvailability.NOT_SELLING;
        }
        Integer stock = product.getStockQuantity();
        if (status == ProductStatus.OUT_OF_STOCK || stock == null || stock <= 0) {
            return WishlistAvailability.OUT_OF_STOCK;
        }
        return WishlistAvailability.AVAILABLE;
    }
}
