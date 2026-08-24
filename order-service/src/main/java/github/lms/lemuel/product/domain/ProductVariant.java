package github.lms.lemuel.product.domain;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.domain.exception.InsufficientStockException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 상품 옵션 (SKU / Variant) 도메인.
 *
 * <p>색상·사이즈 등 옵션 조합 1 개 = SKU 1 개. 옵션 상품의 재고는 {@link Product} 가 아닌
 * 이 객체에서 관리되며, 결제·주문은 productId 가 아닌 variantId(또는 sku) 를 기준으로 동작한다.
 *
 * <p>동시성 정책:
 * <ul>
 *   <li>재고 차감은 {@code DecreaseVariantStockService} 의 원자적 조건부 UPDATE 로 처리 →
 *       동시 차감 폭주에도 락 대기·재시도 없이 초과판매 방지</li>
 *   <li>{@code version} 필드는 {@code @Version} 으로 매핑되어 일반 부분 수정(옵션/가격 변경 등)의
 *       lost update 를 막는다. 차감 경로는 UPDATE 문에서 version 을 직접 +1 한다.</li>
 * </ul>
 */
public class ProductVariant {

    private Long id;
    private final Long productId;
    private final String sku;
    private String optionName;
    private BigDecimal additionalPrice;
    private BigDecimal discountPrice;
    private BigDecimal discountRate;
    private int stockQuantity;
    private long version;
    private ProductVariantStatus status;
    private String optionSignature;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProductVariant create(Long productId, String sku, String optionName,
                                         BigDecimal additionalPrice, int initialStock) {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank()) throw new ProductInvariantViolationException("sku 는 필수");
        if (optionName == null || optionName.isBlank()) {
            throw new ProductInvariantViolationException("optionName 은 필수 (예: '색상:빨강/사이즈:L')");
        }
        if (initialStock < 0) {
            throw new ProductInvariantViolationException("초기 재고는 0 이상");
        }
        BigDecimal price = additionalPrice == null ? BigDecimal.ZERO : additionalPrice;
        return new ProductVariant(null, productId, sku, optionName, price, null, null, initialStock,
                0L, initialStock == 0 ? ProductVariantStatus.OUT_OF_STOCK : ProductVariantStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now());
    }

    public static ProductVariant rehydrate(Long id, Long productId, String sku, String optionName,
                                            BigDecimal additionalPrice, int stockQuantity, long version,
                                            ProductVariantStatus status, LocalDateTime createdAt,
                                            LocalDateTime updatedAt) {
        return rehydrate(id, productId, sku, optionName, additionalPrice, null, null, stockQuantity,
                version, status, createdAt, updatedAt);
    }

    public static ProductVariant rehydrate(Long id, Long productId, String sku, String optionName,
                                            BigDecimal additionalPrice, BigDecimal discountPrice,
                                            BigDecimal discountRate, int stockQuantity, long version,
                                            ProductVariantStatus status, LocalDateTime createdAt,
                                            LocalDateTime updatedAt) {
        return rehydrate(id, productId, sku, optionName, additionalPrice, discountPrice, discountRate,
                stockQuantity, version, status, null, createdAt, updatedAt);
    }

    public static ProductVariant rehydrate(Long id, Long productId, String sku, String optionName,
                                            BigDecimal additionalPrice, BigDecimal discountPrice,
                                            BigDecimal discountRate, int stockQuantity, long version,
                                            ProductVariantStatus status, String optionSignature,
                                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        ProductVariant variant = new ProductVariant(id, productId, sku, optionName, additionalPrice,
                discountPrice, discountRate, stockQuantity, version, status, createdAt, updatedAt);
        variant.optionSignature = optionSignature;
        return variant;
    }

    private ProductVariant(Long id, Long productId, String sku, String optionName,
                           BigDecimal additionalPrice, BigDecimal discountPrice, BigDecimal discountRate,
                           int stockQuantity, long version, ProductVariantStatus status,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.productId = productId;
        this.sku = sku;
        this.optionName = optionName;
        this.additionalPrice = additionalPrice;
        this.discountPrice = discountPrice;
        this.discountRate = discountRate;
        this.stockQuantity = stockQuantity;
        this.version = version;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 재고 차감 도메인 불변식 가드(음수 재고 방지). 고동시성 차감은 영속 계층의 원자적 조건부
     * UPDATE 가 담당하므로, 이 메서드는 단건/검증 용도로만 사용한다.
     */
    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new ProductInvariantViolationException("차감 수량은 양수여야 합니다");
        }
        if (this.status == ProductVariantStatus.DISCONTINUED) {
            throw new InvalidProductStateException("단종된 SKU 는 차감할 수 없습니다: " + sku);
        }
        if (this.stockQuantity < quantity) {
            throw new InsufficientStockException(
                    "재고 부족: sku=" + sku + ", 요청=" + quantity + ", 가용=" + stockQuantity);
        }
        this.stockQuantity -= quantity;
        if (this.stockQuantity == 0) {
            this.status = ProductVariantStatus.OUT_OF_STOCK;
        }
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 이 옵션이 적용된 <b>주문 단가</b>를 계산한다 (할인 적용 후).
     *
     * <p>금액 우선순위(피드백 합의 — 환불 금액 역산도 이 순서를 따른다):
     * <ol>
     *   <li>기준 가격({@code basePrice}) — products.price 스냅샷</li>
     *   <li>+ 옵션 추가금({@code additionalPrice}, 음수 가능)</li>
     *   <li>- 옵션 정액 할인({@code discountPrice})</li>
     *   <li>- 옵션 정률 할인({@code discountRate} %) — 위 (기준가+추가금-정액할인) 에 적용, 원 단위 버림(FLOOR)</li>
     * </ol>
     * 최종 단가가 음수가 되면 0 으로 절삭한다. 두 할인 필드는 null 이면 미적용.
     */
    public BigDecimal effectiveUnitPrice(BigDecimal basePrice) {
        Objects.requireNonNull(basePrice, "basePrice");
        BigDecimal price = basePrice.add(additionalPrice == null ? BigDecimal.ZERO : additionalPrice);
        if (discountPrice != null) {
            price = price.subtract(discountPrice);
        }
        if (discountRate != null && price.signum() > 0) {
            BigDecimal rateDiscount = price.multiply(discountRate)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
            price = price.subtract(rateDiscount);
        }
        return price.signum() < 0 ? BigDecimal.ZERO : price;
    }

    public void increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new ProductInvariantViolationException("증가 수량은 양수여야 합니다");
        }
        this.stockQuantity += quantity;
        if (this.status == ProductVariantStatus.OUT_OF_STOCK && stockQuantity > 0) {
            this.status = ProductVariantStatus.ACTIVE;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void discontinue() {
        this.status = ProductVariantStatus.DISCONTINUED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isAvailable() {
        return status == ProductVariantStatus.ACTIVE && stockQuantity > 0;
    }

    /**
     * 조합 서명 부여 — 이 SKU 가 어떤 옵션 값 조합인지의 기계 식별자({@link OptionSignature}).
     *
     * <p>같은 값을 다시 넣는 것은 no-op 이라 백필을 몇 번 돌려도 안전하다. 반면 <b>다른</b> 서명을
     * 덮어쓰려는 것은 "이 SKU 의 옵션 조합이 바뀌었다"는 뜻인데, 조합이 바뀌면 그건 다른 SKU 다 —
     * 이미 팔린 주문이 가리키는 조합이 소급해서 달라지므로 막는다.
     */
    public void assignOptionSignature(String signature) {
        Objects.requireNonNull(signature, "signature");
        if (this.optionSignature != null && !this.optionSignature.equals(signature)) {
            throw new InvalidProductStateException(
                    "이미 다른 조합 서명이 부여된 SKU 입니다: sku=" + sku);
        }
        this.optionSignature = signature;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean hasOptionSignature() {
        return optionSignature != null;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getOptionName() { return optionName; }
    public BigDecimal getAdditionalPrice() { return additionalPrice; }
    public BigDecimal getDiscountPrice() { return discountPrice; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public int getStockQuantity() { return stockQuantity; }
    public long getVersion() { return version; }
    public ProductVariantStatus getStatus() { return status; }
    public String getOptionSignature() { return optionSignature; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /**
     * Persistence 어댑터에서 INSERT 후 생성된 PK 를 주입할 때만 사용.
     */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1 회만 부여 가능");
        }
        this.id = id;
    }
}
