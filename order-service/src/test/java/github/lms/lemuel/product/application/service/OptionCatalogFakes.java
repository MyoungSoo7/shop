package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.*;
import github.lms.lemuel.product.domain.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 옵션 카탈로그 백필 테스트용 인메모리 포트 구현.
 *
 * <p>백필의 핵심 성질은 <b>멱등</b>이다 — "두 번째 실행은 아무것도 만들지 않는다" 를 확인하려면
 * 호출 검증만 하는 목이 아니라 상태가 실제로 누적되는 저장소가 필요하다. 그래서 목 대신 가짜를 쓴다.
 */
final class OptionCatalogFakes {

    private OptionCatalogFakes() {
    }

    static final class FakeProductVariantPort implements LoadProductVariantPort, SaveProductVariantPort {

        private final Map<Long, ProductVariant> variants = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        ProductVariant add(Long productId, String sku, String optionName) {
            long id = sequence.incrementAndGet();
            ProductVariant variant = ProductVariant.rehydrate(
                    id, productId, sku, optionName, BigDecimal.ZERO, null, null, 10, 0L,
                    ProductVariantStatus.ACTIVE, null, LocalDateTime.now(), LocalDateTime.now());
            variants.put(id, variant);
            return variant;
        }

        @Override
        public Optional<ProductVariant> loadById(Long id) {
            return Optional.ofNullable(variants.get(id));
        }

        @Override
        public Optional<ProductVariant> loadBySku(String sku) {
            return variants.values().stream().filter(v -> v.getSku().equals(sku)).findFirst();
        }

        @Override
        public List<ProductVariant> loadByProductId(Long productId) {
            return variants.values().stream()
                    .filter(v -> v.getProductId().equals(productId))
                    .toList();
        }

        @Override
        public Optional<ProductVariant> loadByOptionSignature(Long productId, String optionSignature) {
            if (optionSignature == null) {
                return Optional.empty();
            }
            return variants.values().stream()
                    .filter(v -> v.getProductId().equals(productId)
                            && optionSignature.equals(v.getOptionSignature()))
                    .findFirst();
        }

        @Override
        public List<Long> findProductIdsWithVariants() {
            return variants.values().stream()
                    .map(ProductVariant::getProductId).distinct().sorted().toList();
        }

        @Override
        public ProductVariant save(ProductVariant variant) {
            if (variant.getId() == null) {
                variant.assignId(sequence.incrementAndGet());
            }
            variants.put(variant.getId(), variant);
            return variant;
        }

        @Override
        public int decreaseStockIfAvailable(Long variantId, int quantity) {
            throw new UnsupportedOperationException("백필 테스트에서 쓰지 않는다");
        }

        @Override
        public int increaseStock(Long variantId, int quantity) {
            throw new UnsupportedOperationException("백필 테스트에서 쓰지 않는다");
        }
    }

    static final class FakeOptionCatalogPort implements LoadOptionCatalogPort, SaveOptionCatalogPort {

        private final Map<Long, OptionAxis> axes = new LinkedHashMap<>();
        private final Map<Long, OptionAxisValue> axisValues = new LinkedHashMap<>();
        private final Map<Long, ProductOptionAxis> productAxes = new LinkedHashMap<>();
        private final Map<Long, ProductOptionValue> productValues = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public Optional<OptionAxis> findAxisByCode(String code) {
            return axes.values().stream().filter(a -> a.getCode().equals(code)).findFirst();
        }

        @Override
        public Optional<OptionAxis> findAxisById(Long axisId) {
            return Optional.ofNullable(axes.get(axisId));
        }

        @Override
        public List<OptionAxis> loadAllAxes() {
            return List.copyOf(axes.values());
        }

        @Override
        public Optional<OptionAxisValue> findAxisValueByCode(Long axisId, String code) {
            return axisValues.values().stream()
                    .filter(v -> v.getAxisId().equals(axisId) && v.getCode().equals(code))
                    .findFirst();
        }

        @Override
        public Optional<OptionAxisValue> findAxisValueById(Long axisValueId) {
            return Optional.ofNullable(axisValues.get(axisValueId));
        }

        @Override
        public List<OptionAxisValue> loadAxisValues(Long axisId) {
            return axisValues.values().stream().filter(v -> v.getAxisId().equals(axisId)).toList();
        }

        @Override
        public List<ProductOptionAxis> loadProductAxes(Long productId) {
            return productAxes.values().stream()
                    .filter(a -> a.getProductId().equals(productId))
                    .sorted(Comparator.comparingInt(ProductOptionAxis::getSortOrder))
                    .toList();
        }

        @Override
        public Optional<ProductOptionAxis> findProductAxis(Long productId, Long axisId) {
            return productAxes.values().stream()
                    .filter(a -> a.getProductId().equals(productId) && a.getAxisId().equals(axisId))
                    .findFirst();
        }

        @Override
        public Optional<ProductOptionAxis> findProductAxisById(Long productOptionAxisId) {
            return Optional.ofNullable(productAxes.get(productOptionAxisId));
        }

        @Override
        public Optional<ProductOptionValue> findProductValueById(Long productOptionValueId) {
            return Optional.ofNullable(productValues.get(productOptionValueId));
        }

        @Override
        public List<ProductOptionValue> loadProductValues(Long productOptionAxisId) {
            return productValues.values().stream()
                    .filter(v -> v.getProductOptionAxisId().equals(productOptionAxisId))
                    .toList();
        }

        @Override
        public Optional<ProductOptionValue> findProductValue(Long productOptionAxisId, Long axisValueId) {
            return productValues.values().stream()
                    .filter(v -> v.getProductOptionAxisId().equals(productOptionAxisId)
                            && v.getAxisValueId().equals(axisValueId))
                    .findFirst();
        }

        @Override
        public OptionAxis saveAxis(OptionAxis axis) {
            OptionAxis stored = axis.getId() != null ? axis
                    : OptionAxis.rehydrate(sequence.incrementAndGet(), axis.getCode(), axis.getName(),
                    axis.getInputType(), axis.isActive());
            axes.put(stored.getId(), stored);
            return stored;
        }

        @Override
        public OptionAxisValue saveAxisValue(OptionAxisValue value) {
            OptionAxisValue stored = value.getId() != null ? value
                    : OptionAxisValue.rehydrate(sequence.incrementAndGet(), value.getAxisId(),
                    value.getCode(), value.getName(), value.getSwatchHex(),
                    value.getSortOrder(), value.isActive());
            axisValues.put(stored.getId(), stored);
            return stored;
        }

        @Override
        public ProductOptionAxis saveProductAxis(ProductOptionAxis axis) {
            ProductOptionAxis stored = axis.getId() != null ? axis
                    : ProductOptionAxis.rehydrate(sequence.incrementAndGet(), axis.getProductId(),
                    axis.getAxisId(), axis.getSortOrder(), axis.isRequired());
            productAxes.put(stored.getId(), stored);
            return stored;
        }

        @Override
        public ProductOptionValue saveProductValue(ProductOptionValue value) {
            ProductOptionValue stored = value.getId() != null ? value
                    : ProductOptionValue.rehydrate(sequence.incrementAndGet(),
                    value.getProductOptionAxisId(), value.getAxisValueId(),
                    value.getSortOrder(), value.isActive());
            productValues.put(stored.getId(), stored);
            return stored;
        }
    }

    /** SKU ↔ 옵션 값 매핑 가짜. (variantId, productOptionAxisId) 를 키로 upsert 한다 — 실제 PK 와 같다. */
    static final class FakeVariantOptionMappingPort implements VariantOptionMappingPort {

        private final Map<String, ProductVariantOptionValue> mappings = new LinkedHashMap<>();

        @Override
        public List<ProductVariantOptionValue> loadByVariantId(Long variantId) {
            return mappings.values().stream()
                    .filter(m -> m.getVariantId().equals(variantId))
                    .sorted(Comparator.comparing(ProductVariantOptionValue::getProductOptionAxisId))
                    .toList();
        }

        @Override
        public List<ProductVariantOptionValue> loadByProductOptionValueId(Long productOptionValueId) {
            return mappings.values().stream()
                    .filter(m -> m.getProductOptionValueId().equals(productOptionValueId))
                    .toList();
        }

        @Override
        public ProductVariantOptionValue save(ProductVariantOptionValue mapping) {
            mappings.put(mapping.getVariantId() + ":" + mapping.getProductOptionAxisId(), mapping);
            return mapping;
        }

        int size() {
            return mappings.size();
        }
    }
}
