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
    /** 이 SKU 를 사 오는 값. null 은 "아직 모른다"이고 0원 매입이 아니다. */
    private BigDecimal purchasePrice;
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
        return rehydrate(id, productId, sku, optionName, additionalPrice, discountPrice, discountRate,
                null, stockQuantity, version, status, optionSignature, createdAt, updatedAt);
    }

    public static ProductVariant rehydrate(Long id, Long productId, String sku, String optionName,
                                            BigDecimal additionalPrice, BigDecimal discountPrice,
                                            BigDecimal discountRate, BigDecimal purchasePrice,
                                            int stockQuantity, long version,
                                            ProductVariantStatus status, String optionSignature,
                                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        ProductVariant variant = new ProductVariant(id, productId, sku, optionName, additionalPrice,
                discountPrice, discountRate, stockQuantity, version, status, createdAt, updatedAt);
        variant.optionSignature = optionSignature;
        variant.purchasePrice = purchasePrice;
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

    /**
     * 매입가를 정하거나 고친다. {@code null} 을 넣으면 "모른다"로 되돌린다 — 잘못 넣은 값을
     * 0 으로 덮는 것과 지우는 것은 다른 일이라, 지울 길을 막아 두면 0원 매입이라는 거짓이 남는다.
     *
     * @throws ProductInvariantViolationException 음수를 넣은 경우. 역마진은 허용하지만
     *         (그건 판매가와의 관계이지 이 값의 문제가 아니다) 음수 매입가는 입력 사고다.
     */
    public void changePurchasePrice(BigDecimal newPurchasePrice) {
        if (newPurchasePrice != null && newPurchasePrice.signum() < 0) {
            throw new ProductInvariantViolationException("매입가는 0 이상이어야 합니다: " + newPurchasePrice);
        }
        this.purchasePrice = newPurchasePrice;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean hasPurchasePrice() {
        return purchasePrice != null;
    }

    /**
     * 이 SKU 1 개를 팔았을 때 남는 금액 = 판매가 - 매입가.
     *
     * <p>판매가는 {@link #effectiveUnitPrice(BigDecimal)} 로 <b>그때그때 계산한</b> 값을 쓴다.
     * 기준가·추가금·할인 중 무엇이 바뀌어도 이 값이 따라 움직여야 하기 때문이다. 마진을 컬럼으로
     * 굳혀 두지 않은 이유가 여기 있다 — 굳힌 값은 넷 중 하나만 바뀌어도 조용히 거짓이 된다.
     *
     * <p>매입가를 모르면 {@code null} 이다. 0 이 아니다. 모르는 것을 0 으로 답하면 마진이
     * 판매가 전액으로 잡혀 리포트가 통째로 부풀어 오른다.
     *
     * <p>결과는 음수일 수 있다(역마진). 깎지 않는다 — 손해 보고 파는 SKU 는 가려야 할 것이 아니라
     * 눈에 띄어야 하는 것이다.
     */
    public BigDecimal marginAmount(BigDecimal basePrice) {
        if (purchasePrice == null) {
            return null;
        }
        return effectiveUnitPrice(basePrice).subtract(purchasePrice);
    }

    /**
     * 마진율(%) = 마진액 / 판매가 × 100. 소수점 둘째 자리에서 반올림한다.
     *
     * <p>분모는 매입가가 아니라 <b>판매가</b>다. 즉 "판 값의 몇 %가 남았나"(매출 총이익률)이지
     * "산 값 대비 몇 % 붙였나"(마크업)가 아니다. 두 숫자는 같은 거래에서도 다르게 나온다 —
     * 1000원에 사서 2000원에 팔면 이익률 50%, 마크업 100%. 어느 쪽인지 적어 두지 않으면
     * 보는 사람마다 다르게 읽는다.
     *
     * <p>매입가를 모르거나 판매가가 0 이면 {@code null} 이다. 0 으로 나눌 수 없고, 0원에 판
     * 물건의 이익률은 정의되지 않는다(무료 증정이 마진 -∞ 로 리포트에 찍히면 곤란하다).
     */
    public BigDecimal marginRate(BigDecimal basePrice) {
        BigDecimal margin = marginAmount(basePrice);
        if (margin == null) {
            return null;
        }
        BigDecimal sellingPrice = effectiveUnitPrice(basePrice);
        if (sellingPrice.signum() == 0) {
            return null;
        }
        return margin.multiply(BigDecimal.valueOf(100))
                .divide(sellingPrice, 2, RoundingMode.HALF_UP);
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
    public BigDecimal getPurchasePrice() { return purchasePrice; }
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
