package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.DescribeVariantOptionsUseCase.OptionDescriptor;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeOptionCatalogPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeProductVariantPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeVariantOptionMappingPort;
import github.lms.lemuel.product.domain.ProductVariant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DescribeVariantOptionsService — SKU 를 주문 스냅샷용 옵션 설명으로 푼다")
class DescribeVariantOptionsServiceTest {

    private static final Long PRODUCT_ID = 1L;

    private FakeProductVariantPort variantPort;
    private FakeOptionCatalogPort catalogPort;
    private FakeVariantOptionMappingPort mappingPort;
    private DescribeVariantOptionsService service;

    @BeforeEach
    void setUp() {
        variantPort = new FakeProductVariantPort();
        catalogPort = new FakeOptionCatalogPort();
        mappingPort = new FakeVariantOptionMappingPort();
        service = new DescribeVariantOptionsService(variantPort, catalogPort, mappingPort);
    }

    private void backfill() {
        new BackfillOptionCatalogService(variantPort, catalogPort, catalogPort).backfillAll();
        new BackfillVariantSignatureService(variantPort, variantPort, catalogPort, mappingPort)
                .backfillAll();
    }

    @Nested
    @DisplayName("카탈로그 경로")
    class FromCatalog {

        @Test
        @DisplayName("매핑된 SKU 는 축·값의 코드와 이름을 차수 순으로 돌려준다")
        void describesFromMappings() {
            ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "색상:빨강/사이즈:L");
            backfill();

            List<OptionDescriptor> descriptors = service.describe(variant.getId());

            assertThat(descriptors).containsExactly(
                    new OptionDescriptor(0, "색상", "색상", "빨강", "빨강"),
                    new OptionDescriptor(1, "사이즈", "사이즈", "L", "L"));
        }

        @Test
        @DisplayName("축 이름이 바뀌어도 스냅샷은 현재 이름을 읽는다 — 스냅샷은 주문 시점에 고정된다")
        void readsCurrentNames() {
            ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "색상:빨강");
            backfill();
            var axis = catalogPort.findAxisByCode("색상").orElseThrow();
            axis.rename("컬러");
            catalogPort.saveAxis(axis);

            assertThat(service.describe(variant.getId()))
                    .singleElement()
                    .extracting(OptionDescriptor::axisName).isEqualTo("컬러");
        }

        @Test
        @DisplayName("3 차 옵션도 차수 순으로 그대로 나온다")
        void handlesDeepAxes() {
            ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "색상:빨강/사이즈:L/각인:AB");
            backfill();

            assertThat(service.describe(variant.getId()))
                    .extracting(OptionDescriptor::sortOrder)
                    .containsExactly(0, 1, 2);
        }
    }

    @Nested
    @DisplayName("레거시 폴백")
    class LegacyFallback {

        @Test
        @DisplayName("매핑이 없는 SKU 는 표시명을 파싱해 같은 모양으로 만든다")
        void parsesLegacyLabel() {
            ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "색상:빨강/사이즈:L");

            assertThat(service.describe(variant.getId())).containsExactly(
                    new OptionDescriptor(0, "색상", "색상", "빨강", "빨강"),
                    new OptionDescriptor(1, "사이즈", "사이즈", "L", "L"));
        }

        @Test
        @DisplayName("코드 규칙은 백필과 동일하다 — 공백은 하이픈으로 접힌다")
        void usesSameCodeRule() {
            ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "메인 색상:밝은 빨강");

            assertThat(service.describe(variant.getId())).containsExactly(
                    new OptionDescriptor(0, "메인-색상", "메인 색상", "밝은-빨강", "밝은 빨강"));
        }

        @Test
        @DisplayName("파싱 불가 표시명은 빈 목록 — 주문 생성이 설명 때문에 실패하면 안 된다")
        void returnsEmptyOnUnparsable() {
            ProductVariant variant = variantPort.add(PRODUCT_ID, "SKU-1", "색상빨강");

            assertThat(service.describe(variant.getId())).isEmpty();
        }

        @Test
        @DisplayName("없는 SKU·null 은 빈 목록이다")
        void returnsEmptyForUnknown() {
            assertThat(service.describe(999L)).isEmpty();
            assertThat(service.describe(null)).isEmpty();
        }
    }
}
