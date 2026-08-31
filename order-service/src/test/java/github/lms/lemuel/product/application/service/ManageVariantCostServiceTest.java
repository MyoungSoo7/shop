package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.ManageVariantCostUseCase.VariantCostView;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SKU 매입가·마진 유스케이스.
 *
 * <p>여기서 확인하는 건 계산식이 아니라(그건 도메인 테스트가 본다) <b>기준가를 어디서 가져오는가</b>와
 * <b>남의 상품 SKU 를 이 경로로 고칠 수 없는가</b>다. 마진은 상품의 기준가 없이는 계산할 수 없어서
 * 유스케이스가 상품을 한 번 읽어야만 하고, 그 상품과 SKU 가 어긋나면 숫자는 나오지만 뜻이 없다.
 */
@DisplayName("ManageVariantCostService — SKU 매입가·마진")
class ManageVariantCostServiceTest {

    private static final Long PRODUCT_ID = 1L;
    private static final BigDecimal BASE_PRICE = new BigDecimal("10000");

    private LoadProductPort loadProductPort;
    private FakeProductVariantPort variantPort;
    private ManageVariantCostService service;

    @BeforeEach
    void setUp() {
        loadProductPort = mock(LoadProductPort.class);
        variantPort = new FakeProductVariantPort();
        service = new ManageVariantCostService(loadProductPort, variantPort, variantPort);
    }

    private void productExists(BigDecimal basePrice) {
        Product product = mock(Product.class);
        when(product.getPrice()).thenReturn(basePrice);
        when(loadProductPort.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
    }

    @Test
    @DisplayName("목록: 매입가를 넣은 SKU 만 마진이 나오고 나머지는 null 로 남는다")
    void listMixesKnownAndUnknownCost() {
        productExists(BASE_PRICE);
        ProductVariant priced = variantPort.add(PRODUCT_ID, "SKU-1", "색상:빨강");
        variantPort.add(PRODUCT_ID, "SKU-2", "색상:파랑");
        service.changePurchasePrice(PRODUCT_ID, priced.getId(), new BigDecimal("6000"));

        List<VariantCostView> views = service.listByProductId(PRODUCT_ID);

        assertThat(views).hasSize(2);
        VariantCostView first = views.get(0);
        assertThat(first.sku()).isEqualTo("SKU-1");
        assertThat(first.sellingPrice()).isEqualByComparingTo("10000");
        assertThat(first.marginAmount()).isEqualByComparingTo("4000");
        assertThat(first.marginRate()).isEqualByComparingTo("40.00");

        VariantCostView second = views.get(1);
        assertThat(second.purchasePrice()).isNull();
        assertThat(second.marginAmount()).isNull();
        assertThat(second.marginRate()).isNull();
        // 매입가를 몰라도 파는 값은 안다 — 마진만 비워 둔다.
        assertThat(second.sellingPrice()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("설정: 저장 후 다시 읽어도 매입가가 남는다")
    void changePersists() {
        productExists(BASE_PRICE);
        ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "색상:빨강");

        VariantCostView view =
                service.changePurchasePrice(PRODUCT_ID, variant.getId(), new BigDecimal("7500"));

        assertThat(view.purchasePrice()).isEqualByComparingTo("7500");
        assertThat(variantPort.loadById(variant.getId()).orElseThrow().getPurchasePrice())
                .isEqualByComparingTo("7500");
    }

    @Test
    @DisplayName("해제: null 을 보내면 마진이 다시 null 이 된다")
    void clearingCostRemovesMargin() {
        productExists(BASE_PRICE);
        ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "색상:빨강");
        service.changePurchasePrice(PRODUCT_ID, variant.getId(), new BigDecimal("7500"));

        VariantCostView cleared = service.changePurchasePrice(PRODUCT_ID, variant.getId(), null);

        assertThat(cleared.purchasePrice()).isNull();
        assertThat(cleared.marginRate()).isNull();
    }

    @Test
    @DisplayName("다른 상품의 SKU 는 이 상품 경로로 고칠 수 없다")
    void rejectsVariantOfAnotherProduct() {
        productExists(BASE_PRICE);
        ProductVariant foreign = variantPort.add(999L, "SKU-X", "색상:빨강");

        assertThatThrownBy(() ->
                service.changePurchasePrice(PRODUCT_ID, foreign.getId(), new BigDecimal("100")))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("것이 아닙니다");
    }

    @Test
    @DisplayName("없는 SKU 는 거부한다")
    void rejectsMissingVariant() {
        productExists(BASE_PRICE);

        assertThatThrownBy(() ->
                service.changePurchasePrice(PRODUCT_ID, 404L, new BigDecimal("100")))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("없는 상품은 거부한다 — 기준가 없이는 마진이 성립하지 않는다")
    void rejectsMissingProduct() {
        when(loadProductPort.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listByProductId(PRODUCT_ID))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("음수 매입가는 도메인 규칙대로 거부된다")
    void rejectsNegativeCost() {
        productExists(BASE_PRICE);
        ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "색상:빨강");

        assertThatThrownBy(() ->
                service.changePurchasePrice(PRODUCT_ID, variant.getId(), new BigDecimal("-1")))
                .isInstanceOf(ProductInvariantViolationException.class);
    }
}
