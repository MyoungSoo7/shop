package github.lms.lemuel.product.application.port.in;

import java.util.ArrayList;
import java.util.List;

/**
 * SKU 를 옵션 카탈로그에 연결한다 — 매핑({@code product_variant_option_values}) 기록과
 * 조합 서명({@code product_variants.option_signature}) 부여.
 *
 * <p>{@link BackfillOptionCatalogUseCase}(Phase 1)가 먼저 돌아 축·값이 존재해야 한다. 없으면 추측해서
 * 만들지 않고 경고로 남긴다 — 여기서 축을 만들기 시작하면 두 백필이 같은 책임을 나눠 갖게 되고,
 * 어느 쪽이 카탈로그의 정본인지 흐려진다.
 */
public interface BackfillVariantSignatureUseCase {

    SignatureBackfillReport backfillAll();

    SignatureBackfillReport backfillProduct(Long productId);

    record SignatureBackfillReport(int productsScanned,
                                   int variantsScanned,
                                   int mappingsWritten,
                                   int signaturesAssigned,
                                   int skipped,
                                   List<String> warnings) {

        public static final int MAX_WARNINGS = 200;

        public SignatureBackfillReport {
            warnings = List.copyOf(warnings);
        }

        public static SignatureBackfillReport empty() {
            return new SignatureBackfillReport(0, 0, 0, 0, 0, List.of());
        }

        public SignatureBackfillReport merge(SignatureBackfillReport other) {
            List<String> merged = new ArrayList<>(warnings);
            for (String warning : other.warnings()) {
                if (merged.size() < MAX_WARNINGS) {
                    merged.add(warning);
                }
            }
            return new SignatureBackfillReport(
                    productsScanned + other.productsScanned(),
                    variantsScanned + other.variantsScanned(),
                    mappingsWritten + other.mappingsWritten(),
                    signaturesAssigned + other.signaturesAssigned(),
                    skipped + other.skipped(),
                    merged);
        }

        /** 모든 SKU 가 서명을 얻었는가 — Phase 3(서명 단건 조회 전환)의 진입 조건. */
        public boolean complete() {
            return skipped == 0;
        }
    }
}
