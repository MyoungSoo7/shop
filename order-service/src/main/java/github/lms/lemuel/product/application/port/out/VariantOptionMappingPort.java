package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductVariantOptionValue;

import java.util.List;

/**
 * SKU ↔ 옵션 값 매핑 포트.
 *
 * <p>조회와 저장을 한 인터페이스에 둔 이유: 매핑은 "쓰고 곧바로 같은 키로 되읽는" 단일 관심사이고,
 * 쓰는 쪽(백필)과 읽는 쪽(파셋 조회)이 같은 키 공간을 공유한다.
 */
public interface VariantOptionMappingPort {

    /** 축 id 오름차순. */
    List<ProductVariantOptionValue> loadByVariantId(Long variantId);

    /** 이 옵션 값을 쓰는 SKU 매핑 — 값 비활성화 영향 범위 확인용. */
    List<ProductVariantOptionValue> loadByProductOptionValueId(Long productOptionValueId);

    /** (variantId, productOptionAxisId) 기준 upsert. 같은 매핑을 다시 써도 안전하다. */
    ProductVariantOptionValue save(ProductVariantOptionValue mapping);
}
