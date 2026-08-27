package github.lms.lemuel.addressbook.application.port.in;

import github.lms.lemuel.addressbook.domain.AddressBook;
import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;

import java.util.Optional;

/**
 * 배송지 주소록 인바운드 포트 — REST 컨트롤러의 단일 진입점.
 *
 * <p><b>기본 배송지 지정이 한 번의 호출이다.</b> 이식 대상이던 레거시는 "전부 기본에서 내린다"와
 * "하나를 기본으로 올린다"가 서로 다른 요청이었다. 화면이 두 번 부르는 사이에 하나라도 실패하면
 * 기본이 0개인 채로 남고, 기본 배송지를 읽는 조회는 조건이 맞는 줄이 없어 <b>아무것도 돌려주지
 * 않는다</b> — 주문서의 배송지 칸이 빈 채로 뜬다. 순서를 화면에 맡기지 않고 {@link #setDefault}
 * 하나로 묶어 한 트랜잭션에서 끝낸다.
 *
 * <p>목록을 돌려주는 메서드들이 {@link AddressBook} 을 통째로 주는 것도 같은 이유다. 어느 줄이
 * 기본인지는 <b>목록 안에서만</b> 뜻이 있는 정보라, 한 줄만 떼어 주면 호출자가 나머지를 다시
 * 읽어 맞춰 봐야 한다.
 */
public interface AddressBookUseCase {

    /** 내 주소록 전체. 기본 배송지가 맨 위다. */
    AddressBook list(Long userId);

    /** 주문서 배송지 칸을 미리 채우기 위한 단건 조회. 주소록이 비어 있을 때만 빈 값이다. */
    Optional<ShippingAddressEntry> findDefault(Long userId);

    /** 새 배송지. 첫 줄이면 요청하지 않아도 기본이 된다. */
    ShippingAddressEntry register(Long userId, AddressForm form);

    /**
     * 내용 수정.
     *
     * <p>{@code form.makeDefault()} 가 참이면 기본 지정까지 함께 한다. 거짓이라고 해서 기본을
     * 내리지는 않는다 — 내리기만 하는 동작은 기본을 0개로 만들 수 있어 제공하지 않는다.
     * 기본을 옮기려면 다른 줄을 올린다.
     */
    ShippingAddressEntry modify(Long userId, Long entryId, AddressForm form);

    /** 기본 배송지 지정. 내리기와 올리기가 한 트랜잭션 안에서 끝난다. */
    AddressBook setDefault(Long userId, Long entryId);

    /** 삭제. 지운 것이 기본이었고 남은 줄이 있으면 그중 하나가 기본으로 승격한다. */
    AddressBook remove(Long userId, Long entryId);

    /**
     * 등록·수정 요청 본문.
     *
     * <p>별칭({@code label})과 받는 사람 이름({@code recipientName})은 <b>다른 칸이다.</b> 레거시는
     * 등록 SQL 이 별칭 자리에 받는 사람 이름을 넣고 수정 SQL 만 별칭을 넣어, 한 번 수정하기 전까지
     * 별칭이 이름의 사본이었다. 여기서는 둘 다 필수이고 서로를 채워 주지 않는다.
     *
     * @param makeDefault 저장과 동시에 기본으로 지정할지. 주소록이 비어 있으면 이 값과 무관하게 기본이 된다
     */
    record AddressForm(
            String label,
            String recipientName,
            String phone,
            String postalCode,
            String address1,
            String address2,
            String deliveryMemo,
            boolean makeDefault
    ) {}
}
