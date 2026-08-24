package github.lms.lemuel.product.domain;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Product Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 * DB 스키마: id, name, description, price, stock_quantity, status, category_id, options_json, created_at, updated_at
 *
 * <p>{@code optionsJson} 은 상품 등록 시점의 <b>원본 옵션 트리</b>를 JSON 문자열(JSONB 저장)로 보관한다.
 * 임의 깊이(무한 뎁스)의 옵션 구조를 그대로 표현하는 진열/표시용 원천이며, 실제 재고 차감은 이 트리를
 * 펼친 {@link ProductVariant}(SKU) 단위로 처리한다 — 표현(JSON)과 재고(SKU)의 책임을 분리한다.
 */
public class Product {

    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stockQuantity;
    private ProductStatus status;
    private Long categoryId;
    private List<Long> tagIds;
    private String optionsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자 — create/rehydrate 팩토리만 통과한다(다른 애그리거트 동형: 생성자 비공개).
    private Product() {
        this.status = ProductStatus.ACTIVE;
        this.stockQuantity = 0;
        this.tagIds = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자 — rehydrate 팩토리 전용(비공개).
    private Product(Long id, String name, String description, BigDecimal price,
                   Integer stockQuantity, ProductStatus status, Long categoryId, List<Long> tagIds,
                   String optionsJson, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity != null ? stockQuantity : 0;
        this.status = status != null ? status : ProductStatus.ACTIVE;
        this.categoryId = categoryId;
        this.tagIds = tagIds != null ? new ArrayList<>(tagIds) : new ArrayList<>();
        this.optionsJson = optionsJson;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static Product create(String name, String description, BigDecimal price, Integer stockQuantity) {
        Product product = new Product();
        product.name = name;
        product.description = description;
        product.price = price;
        product.stockQuantity = stockQuantity;
        product.validateName();
        product.validatePrice();
        product.validateStockQuantity();
        return product;
    }

    /**
     * 옵션 트리(JSON) 를 함께 보관하는 상품 생성. {@code optionsJson} 이 null/blank 면 옵션 없는 상품과 동일.
     */
    public static Product create(String name, String description, BigDecimal price,
                                 Integer stockQuantity, String optionsJson) {
        Product product = create(name, description, price, stockQuantity);
        product.optionsJson = optionsJson != null && optionsJson.isBlank() ? null : optionsJson;
        return product;
    }

    // 도메인 규칙: name 검증
    public void validateName() {
        if (name == null || name.trim().isEmpty()) {
            throw new ProductInvariantViolationException("Product name cannot be empty");
        }
        if (name.length() > 200) {
            throw new ProductInvariantViolationException("Product name must not exceed 200 characters");
        }
    }

    // 도메인 규칙: price 검증
    public void validatePrice() {
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new ProductInvariantViolationException("Product price must be zero or greater");
        }
    }

    // 도메인 규칙: stockQuantity 검증
    public void validateStockQuantity() {
        if (stockQuantity == null || stockQuantity < 0) {
            throw new ProductInvariantViolationException("Stock quantity must be zero or greater");
        }
    }

    // 비즈니스 메서드: 재고 증가
    public void increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new ProductInvariantViolationException("Increase quantity must be positive");
        }
        this.stockQuantity += quantity;
        this.updatedAt = LocalDateTime.now();

        // 재고가 다시 생겼을 때 품절 상태 해제
        if (this.status == ProductStatus.OUT_OF_STOCK && this.stockQuantity > 0) {
            this.status = ProductStatus.ACTIVE;
        }
    }

    // 비즈니스 메서드: 재고 감소
    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new ProductInvariantViolationException("Decrease quantity must be positive");
        }
        if (this.stockQuantity < quantity) {
            throw new InvalidProductStateException("Insufficient stock: requested=" + quantity + ", available=" + this.stockQuantity);
        }
        this.stockQuantity -= quantity;
        this.updatedAt = LocalDateTime.now();

        // 재고가 0이 되면 품절 상태로 변경
        if (this.stockQuantity == 0 && this.status == ProductStatus.ACTIVE) {
            this.status = ProductStatus.OUT_OF_STOCK;
        }
    }

    // 비즈니스 메서드: 가격 변경
    public void changePrice(BigDecimal newPrice) {
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new ProductInvariantViolationException("New price must be zero or greater");
        }
        this.price = newPrice;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 상품 활성화
    public void activate() {
        if (this.status == ProductStatus.DISCONTINUED) {
            throw new InvalidProductStateException("Cannot activate discontinued product");
        }
        if (this.stockQuantity == 0) {
            this.status = ProductStatus.OUT_OF_STOCK;
        } else {
            this.status = ProductStatus.ACTIVE;
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 상품 비활성화
    public void deactivate() {
        if (this.status == ProductStatus.DISCONTINUED) {
            throw new InvalidProductStateException("Cannot deactivate discontinued product");
        }
        this.status = ProductStatus.INACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 상품 단종
    public void discontinue() {
        this.status = ProductStatus.DISCONTINUED;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 상품 정보 업데이트
    public void updateInfo(String name, String description) {
        if (name != null && !name.trim().isEmpty()) {
            this.name = name;
            validateName();
        }
        if (description != null) {
            this.description = description;
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 확인 메서드
    public boolean isAvailableForSale() {
        return this.status == ProductStatus.ACTIVE && this.stockQuantity > 0;
    }

    public boolean hasStock() {
        return this.stockQuantity != null && this.stockQuantity > 0;
    }

    public boolean isActive() {
        return this.status == ProductStatus.ACTIVE;
    }

    public boolean isDiscontinued() {
        return this.status == ProductStatus.DISCONTINUED;
    }

    /**
     * 영속 레코드 복원 팩토리. no-arg + setter 대신 이 경로로만 재구성해 도메인 봉인을 유지한다.
     */
    public static Product rehydrate(Long id, String name, String description, BigDecimal price,
                                    Integer stockQuantity, ProductStatus status, Long categoryId,
                                    List<Long> tagIds, String optionsJson,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Product(id, name, description, price, stockQuantity, status, categoryId,
                tagIds, optionsJson, createdAt, updatedAt);
    }

    /** Persistence 어댑터가 DB 부여 PK 를 주입할 때 사용(setter 대체). */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    /** 태그 목록을 통째로 교체(null 은 빈 목록). 방어적 복사로 외부 리스트와 격리. */
    public void replaceTags(List<Long> tagIds) {
        this.tagIds = tagIds != null ? new ArrayList<>(tagIds) : new ArrayList<>();
        this.updatedAt = LocalDateTime.now();
    }

    // Getters
    public Long getId() {
        return id;
    }


    public String getName() {
        return name;
    }


    public String getDescription() {
        return description;
    }


    public BigDecimal getPrice() {
        return price;
    }


    public Integer getStockQuantity() {
        return stockQuantity;
    }


    public ProductStatus getStatus() {
        return status;
    }


    public LocalDateTime getCreatedAt() {
        return createdAt;
    }


    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }


    public Long getCategoryId() {
        return categoryId;
    }


    /** 원본 옵션 트리(JSON 문자열, JSONB 저장). null 이면 옵션 없는 상품. */
    public String getOptionsJson() {
        return optionsJson;
    }


    public List<Long> getTagIds() {
        return new ArrayList<>(tagIds);
    }


    // 비즈니스 메서드: 태그 추가
    public void addTag(Long tagId) {
        if (tagId == null) {
            throw new ProductInvariantViolationException("Tag ID cannot be null");
        }
        if (!this.tagIds.contains(tagId)) {
            this.tagIds.add(tagId);
            this.updatedAt = LocalDateTime.now();
        }
    }

    // 비즈니스 메서드: 태그 제거
    public void removeTag(Long tagId) {
        this.tagIds.remove(tagId);
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 모든 태그 제거
    public void clearTags() {
        this.tagIds.clear();
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 확인 메서드: 특정 태그 보유 여부
    public boolean hasTag(Long tagId) {
        return this.tagIds.contains(tagId);
    }
}
