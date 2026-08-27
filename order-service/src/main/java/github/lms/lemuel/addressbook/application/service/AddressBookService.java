package github.lms.lemuel.addressbook.application.service;

import github.lms.lemuel.addressbook.application.port.in.AddressBookUseCase;
import github.lms.lemuel.addressbook.application.port.out.LoadAddressBookPort;
import github.lms.lemuel.addressbook.application.port.out.SaveAddressBookPort;
import github.lms.lemuel.addressbook.domain.AddressBook;
import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;
import github.lms.lemuel.addressbook.domain.exception.AddressBookInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 배송지 주소록 운영.
 *
 * <p>여기서 지키는 것은 하나다. <b>비어 있지 않은 주소록에는 기본 배송지가 정확히 하나 있다.</b>
 * 그 절반("둘 이상 불가")은 DB 의 부분 유일 인덱스가 맡고, 나머지 절반("하나도 없으면 안 됨")은
 * 제약으로 표현할 수 없어 이 클래스가 맡는다. 셋을 모두 한 트랜잭션 안에 둔다.
 * <ul>
 *   <li>첫 줄은 요청 여부와 무관하게 기본이 된다 — 그러지 않으면 줄은 있는데 기본이 없다.</li>
 *   <li>기본을 옮길 때는 내리고 올리는 두 문장이 <b>한 트랜잭션</b>이다. 레거시는 이 둘이 서로 다른
 *       요청이었고, 사이에서 끊기면 기본이 0개로 남았다.</li>
 *   <li>기본을 지우면 남은 줄 중 하나가 승격한다 — 지우기와 승격 역시 한 트랜잭션이다.</li>
 * </ul>
 *
 * <p><b>소유자 대조를 코드로 하지 않는다.</b> 조회 포트가 "이 사용자의 줄 전부"만 돌려주므로,
 * 그 안에서 id 를 찾지 못하면 그것은 곧 남의 줄이거나 없는 줄이다({@link AddressBook#require}).
 * 대조를 잊을 수 있는 자리를 아예 만들지 않는 편이, 잊지 않도록 주의하는 것보다 낫다.
 */
@Service
@Transactional
public class AddressBookService implements AddressBookUseCase {

    private final LoadAddressBookPort loadAddressBookPort;
    private final SaveAddressBookPort saveAddressBookPort;

    public AddressBookService(LoadAddressBookPort loadAddressBookPort,
                              SaveAddressBookPort saveAddressBookPort) {
        this.loadAddressBookPort = loadAddressBookPort;
        this.saveAddressBookPort = saveAddressBookPort;
    }

    @Override
    @Transactional(readOnly = true)
    public AddressBook list(Long userId) {
        return load(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShippingAddressEntry> findDefault(Long userId) {
        return load(userId).defaultEntry();
    }

    @Override
    public ShippingAddressEntry register(Long userId, AddressForm form) {
        AddressBook book = load(userId);
        book.requireRoom();
        requireForm(form);

        // 첫 줄이면 사용자가 요청하지 않아도 기본이다. 판단은 주소록이 한다.
        boolean becomesDefault = book.shouldBecomeDefault(form.makeDefault());
        ShippingAddressEntry draft = toDraft(userId, form);

        if (!becomesDefault) {
            return saveAddressBookPort.save(draft);
        }
        // 올리기 전에 내린다. 순서가 뒤집히면 부분 유일 인덱스가 그 자리에서 거부한다.
        saveAddressBookPort.clearDefault(userId);
        return saveAddressBookPort.save(draft.withDefault(true));
    }

    @Override
    public ShippingAddressEntry modify(Long userId, Long entryId, AddressForm form) {
        AddressBook book = load(userId);
        ShippingAddressEntry target = book.require(entryId);
        requireForm(form);

        ShippingAddressEntry updated = target.withContent(
                form.label(), form.recipientName(), form.phone(),
                form.postalCode(), form.address1(), form.address2(), form.deliveryMemo());

        // 이미 기본인 줄을 다시 기본으로 지정하는 것은 아무 일도 아니다. 그런데도 내려 버리면
        // 그 트랜잭션 안에서 기본이 0개인 순간이 생기고, 저장이 실패하면 그대로 남는다.
        if (form.makeDefault() && !target.defaultAddress()) {
            saveAddressBookPort.clearDefault(userId);
            return saveAddressBookPort.save(updated.withDefault(true));
        }
        return saveAddressBookPort.save(updated);
    }

    @Override
    public AddressBook setDefault(Long userId, Long entryId) {
        AddressBook book = load(userId);
        ShippingAddressEntry target = book.require(entryId);

        if (!target.defaultAddress()) {
            saveAddressBookPort.clearDefault(userId);
            saveAddressBookPort.markDefault(entryId);
        }
        return load(userId);
    }

    @Override
    public AddressBook remove(Long userId, Long entryId) {
        AddressBook book = load(userId);
        book.require(entryId);

        // 누가 승계하는지를 먼저 정한다 — 지운 뒤에 정하면 "기본이 없는 주소록"을 한 번 만들어
        // 놓고 고치는 모양이 되고, 그 사이에 실패하면 그 상태가 남는다.
        Optional<ShippingAddressEntry> successor = book.successorAfterRemoving(entryId);
        saveAddressBookPort.deleteById(entryId);
        successor.ifPresent(next -> saveAddressBookPort.markDefault(next.id()));

        return load(userId);
    }

    private AddressBook load(Long userId) {
        requireUser(userId);
        return new AddressBook(userId, loadAddressBookPort.findByUserId(userId));
    }

    /**
     * 필수 항목 검사.
     *
     * <p>값 자체의 규칙(공백·길이)은 {@link ShippingAddressEntry} 의 생성자가 지킨다. 여기서는
     * 폼이 통째로 비어 온 경우만 먼저 끊는다 — 그래야 오류 메시지가 "요청 본문이 없습니다"가 된다.
     */
    private static void requireForm(AddressForm form) {
        if (form == null) {
            throw new AddressBookInvariantViolationException("배송지 정보가 없습니다.");
        }
    }

    private static ShippingAddressEntry toDraft(Long userId, AddressForm form) {
        return ShippingAddressEntry.draft(userId, form.label(), form.recipientName(),
                form.phone(), form.postalCode(), form.address1(),
                form.address2(), form.deliveryMemo());
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new AddressBookInvariantViolationException("userId 필수");
        }
    }
}
