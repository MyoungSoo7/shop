package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.util.List;

/**
 * 상품의 원본 옵션 트리(JSON, 임의 깊이)에서 사용자가 선택한 경로를 펼쳐진 SKU({@link ProductVariant})로
 * 변환하는 인바운드 포트.
 *
 * <p>표현(JSON 트리)과 재고(SKU) 의 책임 분리를 잇는 다리: 상품 등록 시 보관한 {@code products.options_json}
 * 트리를 따라 선택 경로의 유효성을 검증하고, 트리 leaf 에 대응하는 {@code product_variants} 행을 찾아준다.
 * 이후 재고 차감은 반환된 variant(SKU) 단위로 처리한다.
 */
public interface ResolveOptionSelectionUseCase {

    /**
     * 선택 경로를 SKU(variant)로 해석한다.
     *
     * @param productId  대상 상품
     * @param selections 트리 차수 순서대로의 (옵션명, 선택값) 목록. 예: [(색상,빨강),(사이즈,L)]
     * @return 선택 조합에 대응하는 {@link ProductVariant}
     * @throws ProductInvariantViolationException 옵션 트리 미정의 / 차수 이름 불일치 / 없는 값 / 선택 불완전·과다 /
     *                                  대응 SKU 부재 / 트리 JSON 파싱 실패
     */
    ProductVariant resolve(Long productId, List<Selection> selections);

    /** 트리 한 차수의 선택 — (옵션명, 선택값). */
    record Selection(String name, String value) {
        public Selection {
            if (name == null || name.isBlank()) throw new ProductInvariantViolationException("옵션명 필수");
            if (value == null || value.isBlank()) throw new ProductInvariantViolationException("선택값 필수");
        }
    }
}
