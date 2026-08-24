package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.BackfillOptionCatalogUseCase.BackfillReport;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeOptionCatalogPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeProductVariantPort;
import github.lms.lemuel.product.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BackfillOptionCatalogService — 레거시 표시명에서 옵션 카탈로그 역생성")
class BackfillOptionCatalogServiceTest {

    private FakeProductVariantPort variantPort;
    private FakeOptionCatalogPort catalogPort;
    private BackfillOptionCatalogService service;

    @BeforeEach
    void setUp() {
        variantPort = new FakeProductVariantPort();
        catalogPort = new FakeOptionCatalogPort();
        service = new BackfillOptionCatalogService(variantPort, catalogPort, catalogPort);
    }

    @Nested
    @DisplayName("정상 백필")
    class HappyPath {

        @Test
        @DisplayName("단일 축 SKU 하나에서 축·값·상품축·상품값을 각각 하나씩 만든다")
        void singleAxis() {
            variantPort.add(1L, "SKU-1", "색상:빨강");

            BackfillReport report = service.backfillProduct(1L);

            assertThat(report.productsScanned()).isEqualTo(1);
            assertThat(report.variantsScanned()).isEqualTo(1);
            assertThat(report.axesCreated()).isEqualTo(1);
            assertThat(report.axisValuesCreated()).isEqualTo(1);
            assertThat(report.productAxesCreated()).isEqualTo(1);
            assertThat(report.productValuesCreated()).isEqualTo(1);
            assertThat(report.warnings()).isEmpty();

            OptionAxis axis = catalogPort.findAxisByCode("색상").orElseThrow();
            assertThat(axis.getName()).isEqualTo("색상");
            assertThat(axis.getInputType()).isEqualTo(OptionInputType.SELECT);
        }

        @Test
        @DisplayName("2 축 SKU 두 개는 축 2·값 3 으로 접힌다 — 값은 축 단위로 공유된다")
        void twoAxesShareValues() {
            variantPort.add(1L, "SKU-1", "색상:빨강/사이즈:L");
            variantPort.add(1L, "SKU-2", "색상:빨강/사이즈:M");

            BackfillReport report = service.backfillProduct(1L);

            assertThat(report.variantsScanned()).isEqualTo(2);
            assertThat(report.axesCreated()).isEqualTo(2);
            assertThat(report.axisValuesCreated()).isEqualTo(3);
            assertThat(report.productAxesCreated()).isEqualTo(2);
            assertThat(report.productValuesCreated()).isEqualTo(3);
        }

        @Test
        @DisplayName("차수는 표시명의 등장 순서를 따른다")
        void axisDepthFollowsAppearanceOrder() {
            variantPort.add(1L, "SKU-1", "색상:빨강/사이즈:L");

            service.backfillProduct(1L);

            List<ProductOptionAxis> axes = catalogPort.loadProductAxes(1L);
            assertThat(axes).hasSize(2);
            assertThat(axes.get(0).getSortOrder()).isZero();
            assertThat(axes.get(1).getSortOrder()).isEqualTo(1);

            Long firstAxisId = axes.get(0).getAxisId();
            assertThat(catalogPort.findAxisById(firstAxisId).orElseThrow().getName())
                    .isEqualTo("색상");
        }

        @Test
        @DisplayName("3 차 이상 옵션도 그대로 만든다 — 차수 상한이 없다")
        void deepAxes() {
            variantPort.add(1L, "SKU-1", "색상:빨강/사이즈:L/각인:AB");

            BackfillReport report = service.backfillProduct(1L);

            assertThat(report.productAxesCreated()).isEqualTo(3);
            assertThat(catalogPort.loadProductAxes(1L)).hasSize(3);
        }

        @Test
        @DisplayName("축 이름의 내부 공백은 코드에서 하이픈으로 접는다")
        void collapsesWhitespaceInCode() {
            variantPort.add(1L, "SKU-1", "메인 색상:밝은 빨강");

            service.backfillProduct(1L);

            OptionAxis axis = catalogPort.findAxisByCode("메인-색상").orElseThrow();
            assertThat(axis.getName()).isEqualTo("메인 색상");
            assertThat(catalogPort.findAxisValueByCode(axis.getId(), "밝은-빨강").orElseThrow().getName())
                    .isEqualTo("밝은 빨강");
        }

        @Test
        @DisplayName("표준 값은 축 단위로 재사용된다 — 다른 상품이 같은 값을 또 만들지 않는다")
        void reusesAxisValuesAcrossProducts() {
            variantPort.add(1L, "SKU-1", "색상:빨강");
            variantPort.add(2L, "SKU-2", "색상:빨강");

            BackfillReport report = service.backfillAll();

            assertThat(report.productsScanned()).isEqualTo(2);
            assertThat(report.axesCreated()).isEqualTo(1);
            assertThat(report.axisValuesCreated()).isEqualTo(1);
            assertThat(report.productAxesCreated()).isEqualTo(2);
            assertThat(report.productValuesCreated()).isEqualTo(2);
        }

        @Test
        @DisplayName("SKU 가 없는 상품은 아무것도 만들지 않는다")
        void noVariants() {
            BackfillReport report = service.backfillProduct(42L);

            assertThat(report.variantsScanned()).isZero();
            assertThat(report.createdNothing()).isTrue();
        }
    }

    @Nested
    @DisplayName("멱등")
    class Idempotency {

        @Test
        @DisplayName("두 번째 실행은 아무것도 만들지 않는다")
        void secondRunCreatesNothing() {
            variantPort.add(1L, "SKU-1", "색상:빨강/사이즈:L");
            variantPort.add(1L, "SKU-2", "색상:파랑/사이즈:M");

            BackfillReport first = service.backfillAll();
            BackfillReport second = service.backfillAll();

            assertThat(first.createdNothing()).isFalse();
            assertThat(second.createdNothing()).isTrue();
            assertThat(second.variantsScanned()).isEqualTo(2);
            assertThat(catalogPort.loadProductAxes(1L)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("이상 데이터")
    class BadData {

        @Test
        @DisplayName("파싱 불가 SKU 는 경고로 남기고 나머지는 계속 처리한다")
        void skipsUnparsableVariant() {
            variantPort.add(1L, "SKU-BAD", "색상빨강");
            variantPort.add(1L, "SKU-OK", "색상:빨강");

            BackfillReport report = service.backfillProduct(1L);

            assertThat(report.variantsScanned()).isEqualTo(2);
            assertThat(report.axesCreated()).isEqualTo(1);
            assertThat(report.warnings())
                    .hasSize(1)
                    .allSatisfy(w -> assertThat(w).contains("SKU-BAD").contains("건너뜀"));
        }

        @Test
        @DisplayName("코드 길이 50 자를 넘는 이름은 축약하지 않고 건너뛴다 — 축약은 서로 다른 값을 합쳐버린다")
        void skipsOverlongName() {
            variantPort.add(1L, "SKU-LONG", "가".repeat(51) + ":빨강");

            BackfillReport report = service.backfillProduct(1L);

            assertThat(report.createdNothing()).isTrue();
            assertThat(report.warnings())
                    .singleElement().asString().contains("50");
        }

        @Test
        @DisplayName("같은 축이 두 번 나오는 표시명은 건너뛴다")
        void skipsDuplicateAxis() {
            variantPort.add(1L, "SKU-DUP", "색상:빨강/색상:파랑");

            BackfillReport report = service.backfillProduct(1L);

            assertThat(report.createdNothing()).isTrue();
            assertThat(report.warnings()).singleElement().asString().contains("SKU-DUP");
        }

        @Test
        @DisplayName("상품 안에서 축 순서가 뒤집힌 SKU 는 차수 불일치를 경고한다")
        void warnsOnAxisOrderMismatch() {
            variantPort.add(1L, "SKU-1", "색상:빨강/사이즈:L");
            variantPort.add(1L, "SKU-2", "사이즈:M/색상:파랑");

            BackfillReport report = service.backfillProduct(1L);

            assertThat(report.productAxesCreated()).isEqualTo(2);
            assertThat(report.warnings())
                    .isNotEmpty()
                    .anySatisfy(w -> assertThat(w).contains("차수 불일치"));
        }
    }

    @Nested
    @DisplayName("리포트")
    class Report {

        @Test
        @DisplayName("경고는 상한까지만 누적한다")
        void capsWarnings() {
            BackfillReport report = BackfillReport.empty();
            for (int i = 0; i < BackfillReport.MAX_WARNINGS + 10; i++) {
                report = report.merge(new BackfillReport(0, 0, 0, 0, 0, 0, List.of("w" + i)));
            }

            assertThat(report.warnings()).hasSize(BackfillReport.MAX_WARNINGS);
        }

        @Test
        @DisplayName("merge 는 건수를 합산한다")
        void mergesCounts() {
            BackfillReport a = new BackfillReport(1, 2, 3, 4, 5, 6, List.of());
            BackfillReport b = new BackfillReport(1, 1, 1, 1, 1, 1, List.of());

            BackfillReport merged = a.merge(b);

            assertThat(merged.productsScanned()).isEqualTo(2);
            assertThat(merged.variantsScanned()).isEqualTo(3);
            assertThat(merged.axesCreated()).isEqualTo(4);
            assertThat(merged.axisValuesCreated()).isEqualTo(5);
            assertThat(merged.productAxesCreated()).isEqualTo(6);
            assertThat(merged.productValuesCreated()).isEqualTo(7);
        }

        @Test
        @DisplayName("경고 목록은 불변이다")
        void warningsAreImmutable() {
            BackfillReport report = BackfillReport.empty();

            assertThatThrownBy(() -> report.warnings().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

}
