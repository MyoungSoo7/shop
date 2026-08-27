package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoadProductPort {

    Optional<Product> findById(Long productId);

    /**
     * 여러 상품을 한 번에. 존재하지 않는 id 는 결과에서 빠진다(순서·개수를 보장하지 않는다).
     *
     * <p>찜·장바구니처럼 상품 <b>목록</b>을 그리는 화면을 위한 것이다. 단건 {@link #findById} 를
     * 루프에서 부르면 항목 수만큼 쿼리가 나가고, 그 비용은 화면 응답 시간으로 그대로 드러난다.
     */
    List<Product> findAllByIds(Collection<Long> productIds);

    Optional<Product> findByName(String name);

    List<Product> findAll();

    List<Product> findByStatus(ProductStatus status);

    List<Product> findAvailableProducts();

    List<Product> search(String keyword, Long categoryId, String sortBy, String sortDirection);

    boolean existsByName(String name);
}
