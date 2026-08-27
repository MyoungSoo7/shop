package github.lms.lemuel.addressbook.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 배송지 주소록 행.
 *
 * <p>{@code label}(별칭)과 {@code recipientName}(받는 사람 이름)은 서로 다른 칸이며 서로를 채워
 * 주지 않는다. 레거시는 등록 SQL 만 별칭 자리에 이름을 넣어, 한 번 수정하기 전까지 별칭이 이름의
 * 사본이었다.
 *
 * <p>회원당 기본 배송지가 하나뿐이라는 것은 이 클래스가 아니라 <b>DB 의 부분 유일 인덱스</b>
 * ({@code uk_shipping_address_book_user_default}) 가 지킨다. JPA 로는 "여러 행에 걸친 조건"을
 * 표현할 수 없다.
 */
@Entity
@Table(name = "shipping_address_book")
public class ShippingAddressBookJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "label", nullable = false, length = 50)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "phone", nullable = false, length = 40)
    private String phone;

    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "address1", nullable = false)
    private String address1;

    @Column(name = "address2")
    private String address2;

    @Column(name = "delivery_memo")
    private String deliveryMemo;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ShippingAddressBookJpaEntity() { }

    public ShippingAddressBookJpaEntity(Long id, Long userId, String label, String recipientName,
                                        String phone, String postalCode, String address1,
                                        String address2, String deliveryMemo, boolean defaultAddress,
                                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.postalCode = postalCode;
        this.address1 = address1;
        this.address2 = address2;
        this.deliveryMemo = deliveryMemo;
        this.defaultAddress = defaultAddress;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getLabel() { return label; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getPostalCode() { return postalCode; }
    public String getAddress1() { return address1; }
    public String getAddress2() { return address2; }
    public String getDeliveryMemo() { return deliveryMemo; }
    public boolean isDefaultAddress() { return defaultAddress; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
