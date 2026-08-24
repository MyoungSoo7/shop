package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeOptionCatalogPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeProductVariantPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeVariantOptionMappingPort;
import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SKU 생성 — 생성과 동시에 옵션 카탈로그가 채워지는지까지 확인한다.
 *
 * <p>목이 아니라 인메모리 가짜를 쓰는 이유: "축·값이 실제로 만들어졌는가", "서명이 붙었는가" 는
 * 호출 여부가 아니라 <b>누적된 상태</b>로만 확인할 수 있다.
 */
@DisplayName("ProductVariantService — SKU 생성과 옵션 카탈로그 등록")
class ProductVariantServiceTest {

    private static final Long PRODUCT_ID = 1L;

    private LoadProductPort loadProductPort;
    private FakeProductVariantPort variantPort;
    private FakeOptionCatalogPort catalogPort;
    private ProductVariantService service;

    @BeforeEach
    void setUp() {
        loadProductPort = mock(LoadProductPort.class);
        variantPort = new FakeProductVariantPort();
        catalogPort = new FakeOptionCatalogPort();
        FakeVariantOptionMappingPort mappingPort = new FakeVariantOptionMappingPort();
        service = new ProductVariantService(loadProductPort, variantPort, variantPort,
                new BackfillOptionCatalogService(variantPort, catalogPort, catalogPort),
                new BackfillVariantSignatureService(variantPort, variantPort, catalogPort, mappingPort));
    }

    private void productExists() {
        when(loadProductPort.findById(PRODUCT_ID)).thenReturn(Optional.of(mock(Product.class)));
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("SKU 를 만들면서 축·값을 등록하고 조합 서명을 부여한다")
        void registersCatalogAndSignature() {
            productExists();

            ProductVariant created = service.create(PRODUCT_ID, "SKU-001", "색상:빨강/사이즈:L",
                    BigDecimal.ZERO, 50);

            assertThat(created.getSku()).isEqualTo("SKU-001");
            assertThat(created.hasOptionSignature()).isTrue();

            OptionAxis color = catalogPort.findAxisByCode("색상").orElseThrow();
            assertThat(color.getName()).isEqualTo("색상");
            assertThat(catalogPort.findAxisValueByCode(color.getId(), "빨강")).isPresent();
            assertThat(catalogPort.loadProductAxes(PRODUCT_ID)).hasSize(2);
        }

        @Test
        @DisplayName("두 번째 SKU 는 이미 만들어진 축을 재사용한다")
        void reusesExistingAxes() {
            productExists();
            service.create(PRODUCT_ID, "SKU-001", "색상:빨강/사이즈:L", BigDecimal.ZERO, 10);

            service.create(PRODUCT_ID, "SKU-002", "색상:빨강/사이즈:M", BigDecimal.ZERO, 10);

            assertThat(catalogPort.loadAllAxes()).hasSize(2);
            assertThat(catalogPort.loadProductAxes(PRODUCT_ID)).hasSize(2);
        }

        @Test
        @DisplayName("서로 다른 SKU 는 서로 다른 서명을 받는다")
        void distinctSignatures() {
            productExists();

            ProductVariant l = service.create(PRODUCT_ID, "SKU-L", "색상:빨강/사이즈:L", BigDecimal.ZERO, 10);
            ProductVariant m = service.create(PRODUCT_ID, "SKU-M", "색상:빨강/사이즈:M", BigDecimal.ZERO, 10);

            assertThat(l.getOptionSignature()).isNotEqualTo(m.getOptionSignature());
        }
    }

    @Nested
    @DisplayName("거부")
    class Rejections {

        @Test
        @DisplayName("'축:값' 형식이 아닌 표시명은 거부한다 — 등록할 수 없는 SKU 를 만들지 않는다")
        void rejectsUnparsableOptionName() {
            productExists();

            assertThatThrownBy(() ->
                    service.create(PRODUCT_ID, "SKU-BAD", "빨강/L", BigDecimal.ZERO, 10))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("축:값");
        }

        @Test
        @DisplayName("표시명 순서만 다른 중복 조합은 거부한다")
        void rejectsDuplicateCombination() {
            productExists();
            service.create(PRODUCT_ID, "SKU-A", "색상:빨강/사이즈:L", BigDecimal.ZERO, 10);

            assertThatThrownBy(() ->
                    service.create(PRODUCT_ID, "SKU-B", "사이즈:L/색상:빨강", BigDecimal.ZERO, 10))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("중복");
        }

        @Test
        @DisplayName("상품이 없으면 거부한다")
        void rejectsMissingProduct() {
            when(loadProductPort.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.create(PRODUCT_ID, "SKU", "색상:빨강", BigDecimal.ZERO, 10))
                    .isInstanceOf(ProductNotFoundException.class);
        }

        @Test
        @DisplayName("SKU 가 중복이면 거부한다")
        void rejectsDuplicateSku() {
            productExists();
            service.create(PRODUCT_ID, "DUP", "색상:빨강", BigDecimal.ZERO, 10);

            assertThatThrownBy(() ->
                    service.create(PRODUCT_ID, "DUP", "색상:파랑", BigDecimal.ZERO, 10))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("이미 사용 중인 SKU");
        }
    }

    @Test
    @DisplayName("상품별 SKU 목록을 돌려준다")
    void listByProductId() {
        productExists();
        service.create(PRODUCT_ID, "SKU-001", "색상:빨강", BigDecimal.ZERO, 10);

        assertThat(service.listByProductId(PRODUCT_ID)).hasSize(1);
        assertThat(service.listByProductId(999L)).isEmpty();
    }
}
