package github.lms.lemuel.product.application.port.in;

import java.util.ArrayList;
import java.util.List;

/**
 * 기존 {@code product_variants.option_name} 문자열에서 옵션 축/값 카탈로그를 역생성한다.
 *
 * <p>옵션 축·값이 테이블로 존재하지 않던 시절의 SKU 를 새 카탈로그 구조로 끌어올리는 일회성 경로다.
 * <b>멱등</b>해야 한다 — 두 번 돌려도 두 번째 실행의 생성 건수는 모두 0 이어야 한다.
 */
public interface BackfillOptionCatalogUseCase {

    /** SKU 를 가진 전 상품을 훑는다. */
    BackfillReport backfillAll();

    /** 상품 1 건만 처리한다. */
    BackfillReport backfillProduct(Long productId);

    /**
     * 백필 결과. 생성 건수와 함께 <b>건너뛴 이유</b>를 남긴다 — 파싱 불가 데이터를 조용히 삼키면
     * "성공했는데 일부 SKU 만 카탈로그에 없는" 상태가 되어 이후 단계에서 원인 추적이 어려워진다.
     */
    record BackfillReport(int productsScanned,
                          int variantsScanned,
                          int axesCreated,
                          int axisValuesCreated,
                          int productAxesCreated,
                          int productValuesCreated,
                          List<String> warnings) {

        /** 경고 폭주를 막는 상한 — 초과분은 개수만 남긴다. */
        public static final int MAX_WARNINGS = 200;

        public BackfillReport {
            warnings = List.copyOf(warnings);
        }

        public static BackfillReport empty() {
            return new BackfillReport(0, 0, 0, 0, 0, 0, List.of());
        }

        public BackfillReport merge(BackfillReport other) {
            List<String> merged = new ArrayList<>(warnings);
            for (String warning : other.warnings()) {
                if (merged.size() < MAX_WARNINGS) {
                    merged.add(warning);
                }
            }
            return new BackfillReport(
                    productsScanned + other.productsScanned(),
                    variantsScanned + other.variantsScanned(),
                    axesCreated + other.axesCreated(),
                    axisValuesCreated + other.axisValuesCreated(),
                    productAxesCreated + other.productAxesCreated(),
                    productValuesCreated + other.productValuesCreated(),
                    merged);
        }

        /** 이번 실행이 아무것도 만들지 않았는가 — 멱등 재실행의 기대 상태. */
        public boolean createdNothing() {
            return axesCreated == 0 && axisValuesCreated == 0
                    && productAxesCreated == 0 && productValuesCreated == 0;
        }
    }
}
