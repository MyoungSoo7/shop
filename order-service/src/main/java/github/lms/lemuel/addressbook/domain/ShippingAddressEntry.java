package github.lms.lemuel.addressbook.domain;

import github.lms.lemuel.addressbook.domain.exception.AddressBookInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 주소록에 저장된 배송지 한 줄.
 *
 * <p><b>별칭({@code label})과 받는 사람 이름({@code recipientName})은 다른 것이다.</b> 레거시는
 * 등록 SQL 이 별칭 칸에 {@code #{cmmName}}(받는 사람 이름)을, 수정 SQL 이 {@code #{cmmAddnickname}}
 * (별칭)을 넣고 있었다. 그래서 "회사"라고 적고 저장해도 목록에는 받는 사람 이름이 떴고, 한 번
 * 수정하기 전까지는 모든 줄의 별칭이 이름의 사본이었다 — 가족에게 보내는 주소 다섯 개가 전부
 * 같은 글자로 보인다. 두 값은 여기서 각각 필수이며 서로를 채워 주지 않는다.
 *
 * <p>주문서에 실리는 배송지 스냅샷과 필드가 겹치지만 <b>같은 타입이 아니고, 여기서 그 타입을
 * 참조하지도 않는다.</b> 주소록의 줄은 계속 고쳐지는 살아 있는 자료이고, 스냅샷은 주문서에 굳어
 * 다시는 바뀌지 않는 기록이다. 한 타입으로 묶으면 주소록을 고칠 때 과거 주문서의 뜻까지 흔들린다.
 *
 * <p>변환 메서드도 두지 않는다. 부를 사람이 서버에 없어서다 — 주소록에서 고르는 일은 화면이 하고,
 * 주문 API 는 지금도 배송지를 값으로 받는다. 그 한 줄을 편하자고 두면 {@code addressbook → order}
 * 라는 조각 간 의존이 생기는데, 아키텍처 테스트가 조각 사이 순환을 금지하므로 나중에 주문 쪽이
 * 주소록을 읽어야 할 때 그 의존이 정확히 걸림돌이 된다. 필요해지면 그때 양쪽이 아는 공용 자리에
 * 놓는다.
 *
 * @param id            저장소가 발급한 식별자. 아직 저장 전이면 null
 * @param userId        소유자
 * @param label         사용자가 붙인 별칭('집', '회사')
 * @param defaultAddress 기본 배송지 여부. 이 값을 직접 뒤집지 않고 {@link AddressBook} 을 거친다
 */
public record ShippingAddressEntry(
        Long id,
        Long userId,
        String label,
        String recipientName,
        String phone,
        String postalCode,
        String address1,
        String address2,
        String deliveryMemo,
        boolean defaultAddress,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** 별칭 길이 상한. 스키마의 VARCHAR(50) 과 같은 값이며, DB 가 자르기 전에 도메인이 거절한다. */
    public static final int MAX_LABEL_LENGTH = 50;

    public ShippingAddressEntry {
        Objects.requireNonNull(userId, "userId");
        label = requireText(label, "배송지 별칭");
        recipientName = requireText(recipientName, "받는 분");
        phone = requireText(phone, "연락처");
        postalCode = requireText(postalCode, "우편번호");
        address1 = requireText(address1, "주소");
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new AddressBookInvariantViolationException(
                    "배송지 별칭은 " + MAX_LABEL_LENGTH + "자까지 쓸 수 있습니다.");
        }
        address2 = blankToNull(address2);
        deliveryMemo = blankToNull(deliveryMemo);
    }

    /** 아직 저장되지 않은 줄. 기본 여부는 {@link AddressBook} 이 정하므로 여기서 받지 않는다. */
    public static ShippingAddressEntry draft(Long userId,
                                             String label,
                                             String recipientName,
                                             String phone,
                                             String postalCode,
                                             String address1,
                                             String address2,
                                             String deliveryMemo) {
        return new ShippingAddressEntry(null, userId, label, recipientName, phone,
                postalCode, address1, address2, deliveryMemo, false, null, null);
    }

    /**
     * 내용은 그대로 두고 기본 여부만 바꾼 사본.
     *
     * <p>레코드라 값이 바뀌지 않으므로 "내렸다가 올리는" 두 동작이 한 객체에 겹쳐 남지 않는다.
     */
    public ShippingAddressEntry withDefault(boolean value) {
        return new ShippingAddressEntry(id, userId, label, recipientName, phone,
                postalCode, address1, address2, deliveryMemo, value, createdAt, updatedAt);
    }

    /** 사용자가 고친 내용을 반영한 사본. 소유자·식별자·기본 여부는 요청이 건드릴 수 없다. */
    public ShippingAddressEntry withContent(String newLabel,
                                            String newRecipientName,
                                            String newPhone,
                                            String newPostalCode,
                                            String newAddress1,
                                            String newAddress2,
                                            String newDeliveryMemo) {
        return new ShippingAddressEntry(id, userId, newLabel, newRecipientName, newPhone,
                newPostalCode, newAddress1, newAddress2, newDeliveryMemo,
                defaultAddress, createdAt, updatedAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AddressBookInvariantViolationException(field + " — 필수 항목입니다.");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
