package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.BackfillVariantSignatureUseCase.SignatureBackfillReport;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeOptionCatalogPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeProductVariantPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeVariantOptionMappingPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantOptionValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BackfillVariantSignatureService — SKU 매핑·조합 서명 백필")
class BackfillVariantSignatureServiceTest {

    private FakeProductVariantPort variantPort;
    private FakeOptionCatalogPort catalogPort;
    private FakeVariantOptionMappingPort mappingPort;
    private BackfillOptionCatalogService catalogBackfill;
    private BackfillVariantSignatureService service;

    @BeforeEach
    void setUp() {
        variantPort = new FakeProductVariantPort();
        catalogPort = new FakeOptionCatalogPort();
        mappingPort = new FakeVariantOptionMappingPort();
        catalogBackfill = new BackfillOptionCatalogService(variantPort, catalogPort, catalogPort);
        service = new BackfillVariantSignatureService(variantPort, variantPort, catalogPort, mappingPort);
    }

    /** 실제 운영 순서: 카탈로그 백필(Phase 1) → 서명 백필(Phase 2). */
    private void runCatalogBackfillFirst() {
        catalogBackfill.backfillAll();
    }

    @Nested
    @DisplayName("정상 백필")
    class HappyPath {

        @Test
        @DisplayName("2 축 SKU 에 축 수만큼 매핑을 쓰고 서명을 부여한다")
        void writesMappingsAndSignature() {
            variantPort.add(1L, "SKU-1", "색상:빨강/사이즈:L");
            runCatalogBackfillFirst();

            SignatureBackfillReport report = service.backfillProduct(1L);

            assertThat(report.variantsScanned()).isEqualTo(1);
            assertThat(report.mappingsWritten()).isEqualTo(2);
            assertThat(report.signaturesAssigned()).isEqualTo(1);
            assertThat(report.skipped()).isZero();
            assertThat(report.complete()).isTrue();

            ProductVariant variant = variantPort.loadBySku("SKU-1").orElseThrow();
            assertThat(variant.getOptionSignature()).hasSize(64);

            List<ProductVariantOptionValue> mappings = mappingPort.loadByVariantId(variant.getId());
            assertThat(mappings).hasSize(2);
        }

        @Test
        @DisplayName("서로 다른 조합은 서로 다른 서명을 받는다")
        void distinctCombinationsGetDistinctSignatures() {
            variantPort.add(1L, "SKU-RL", "색상:빨강/사이즈:L");
            variantPort.add(1L, "SKU-RM", "색상:빨강/사이즈:M");
            variantPort.add(1L, "SKU-BL", "색상:파랑/사이즈:L");
            runCatalogBackfillFirst();

            SignatureBackfillReport report = service.backfillProduct(1L);

            assertThat(report.signaturesAssigned()).isEqualTo(3);
            assertThat(List.of("SKU-RL", "SKU-RM", "SKU-BL").stream()
                    .map(sku -> variantPort.loadBySku(sku).orElseThrow().getOptionSignature())
                    .distinct())
                    .hasSize(3);
        }

        @Test
        @DisplayName("여러 상품을 훑고 상품 수를 합산한다")
        void scansAllProducts() {
            variantPort.add(1L, "SKU-1", "색상:빨강");
            variantPort.add(2L, "SKU-2", "색상:파랑");
            runCatalogBackfillFirst();

            SignatureBackfillReport report = service.backfillAll();

            assertThat(report.productsScanned()).isEqualTo(2);
            assertThat(report.signaturesAssigned()).isEqualTo(2);
        }

        @Test
        @DisplayName("SKU 가 없는 상품은 아무것도 하지 않는다")
        void noVariants() {
            SignatureBackfillReport report = service.backfillProduct(99L);

            assertThat(report.variantsScanned()).isZero();
            assertThat(report.signaturesAssigned()).isZero();
            assertThat(report.complete()).isTrue();
        }
    }

    @Nested
    @DisplayName("멱등")
    class Idempotency {

        @Test
        @DisplayName("두 번째 실행은 서명을 다시 부여하지 않는다")
        void secondRunAssignsNothing() {
            variantPort.add(1L, "SKU-1", "색상:빨강/사이즈:L");
            runCatalogBackfillFirst();

            SignatureBackfillReport first = service.backfillAll();
            SignatureBackfillReport second = service.backfillAll();

            assertThat(first.signaturesAssigned()).isEqualTo(1);
            assertThat(second.signaturesAssigned()).isZero();
            assertThat(second.variantsScanned()).isEqualTo(1);
        }

        @Test
        @DisplayName("매핑은 같은 키로 덮어써 개수가 늘지 않는다")
        void mappingsDoNotDuplicate() {
            variantPort.add(1L, "SKU-1", "색상:빨강/사이즈:L");
            runCatalogBackfillFirst();

            service.backfillAll();
            service.backfillAll();

            assertThat(mappingPort.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("이상 데이터")
    class BadData {

        @Test
        @DisplayName("카탈로그 백필을 건너뛰면 만들지 않고 경고한다 — 축 생성 책임은 Phase 1 에 있다")
        void skipsWhenCatalogMissing() {
            variantPort.add(1L, "SKU-1", "색상:빨강");

            SignatureBackfillReport report = service.backfillProduct(1L);

            assertThat(report.signaturesAssigned()).isZero();
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.complete()).isFalse();
            assertThat(report.warnings())
                    .singleElement().asString().contains("표준 축이 없습니다");
        }

        @Test
        @DisplayName("표시명 순서만 다른 중복 조합은 두 번째를 건너뛴다 — 유니크 위반을 DB 로 흘리지 않는다")
        void skipsDuplicateCombination() {
            variantPort.add(1L, "SKU-A", "색상:빨강/사이즈:L");
            variantPort.add(1L, "SKU-B", "사이즈:L/색상:빨강");
            runCatalogBackfillFirst();

            SignatureBackfillReport report = service.backfillProduct(1L);

            assertThat(report.signaturesAssigned()).isEqualTo(1);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.warnings())
                    .singleElement().asString().contains("SKU-B").contains("SKU-A");
            assertThat(variantPort.loadBySku("SKU-B").orElseThrow().hasOptionSignature()).isFalse();
        }

        @Test
        @DisplayName("파싱 불가 표시명은 건너뛰고 나머지는 계속 처리한다")
        void skipsUnparsable() {
            variantPort.add(1L, "SKU-OK", "색상:빨강");
            variantPort.add(1L, "SKU-BAD", "색상빨강");
            runCatalogBackfillFirst();

            SignatureBackfillReport report = service.backfillProduct(1L);

            assertThat(report.signaturesAssigned()).isEqualTo(1);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.warnings()).singleElement().asString().contains("SKU-BAD");
        }
    }

    @Nested
    @DisplayName("리포트")
    class Report {

        @Test
        @DisplayName("merge 는 건수를 합산하고 경고를 상한까지만 쌓는다")
        void merges() {
            SignatureBackfillReport report = SignatureBackfillReport.empty();
            for (int i = 0; i < SignatureBackfillReport.MAX_WARNINGS + 5; i++) {
                report = report.merge(
                        new SignatureBackfillReport(1, 1, 1, 1, 1, List.of("w" + i)));
            }

            assertThat(report.warnings()).hasSize(SignatureBackfillReport.MAX_WARNINGS);
            assertThat(report.productsScanned()).isEqualTo(SignatureBackfillReport.MAX_WARNINGS + 5);
            assertThat(report.complete()).isFalse();
        }
    }
}
