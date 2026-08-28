package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.application.port.in.DescribeProductOptionsUseCase;
import github.lms.lemuel.product.application.port.in.DescribeProductOptionsUseCase.ProductOptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매자용 옵션 트리 조회 엔드포인트.
 *
 * <p>{@link ProductVariantController} 와 경로 접두사가 겹치지 않게 {@code /options} 로 나눈 이유:
 * {@code /products/*&#47;variants} 는 SecurityConfig 에서 ADMIN 으로 잠겨 있다(SKU 재고·낙관락 버전이
 * 나가는 경로다). 구매자가 부를 수 있어야 하는 것은 "무엇을 고를 수 있는가" 뿐이라, 같은 컨트롤러에
 * 메서드를 하나 더 다는 대신 경로 자체를 분리해 인가 매처가 한눈에 갈라지게 둔다.
 */
@Tag(name = "Product Options", description = "구매자용 옵션 트리 조회")
@RestController
@RequestMapping("/products/{productId}/options")
public class ProductOptionController {

    private final DescribeProductOptionsUseCase describeUseCase;

    public ProductOptionController(DescribeProductOptionsUseCase describeUseCase) {
        this.describeUseCase = describeUseCase;
    }

    @Operation(summary = "상품의 옵션 축/값과 판매 중인 조합",
            description = "구매자 화면이 옵션 선택 UI 를 그리기 위한 읽기 전용 트리. "
                    + "선택 → SKU 변환은 POST /products/{productId}/variants/resolve 가 담당한다 — "
                    + "이 응답에는 variantId 도 재고 수량도 들어 있지 않다.")
    @GetMapping
    public ResponseEntity<ProductOptions> describe(@PathVariable Long productId) {
        return ResponseEntity.ok(describeUseCase.describe(productId));
    }
}
