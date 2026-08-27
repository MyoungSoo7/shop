package github.lms.lemuel.addressbook.domain;

import github.lms.lemuel.addressbook.domain.exception.AddressBookInvariantViolationException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 한 사용자의 배송지 주소록 전체.
 *
 * <p>이 타입이 있는 이유는 하나다. <b>"비어 있지 않은 주소록에는 기본 배송지가 정확히 하나"</b> 라는
 * 규칙을 여기 말고는 둘 데가 없어서다. DB 는 부분 유일 인덱스로 "둘 이상"만 막을 수 있다. "하나도
 * 없음"은 빈 주소록이라는 정상 상태와 구분되지 않으므로 제약으로 쓸 수 없다. 레거시가 정확히 그
 * 틈으로 샜다 — 전부 내리는 문장과 하나를 올리는 문장이 서로 다른 요청이라, 사이에서 실패하면
 * 기본이 0개인 채로 남았고 기본 배송지 조회가 아무것도 돌려주지 않았다.
 *
 * <p>그래서 "누가 기본이 되어야 하는가"의 판단을 전부 이리로 모은다. 저장은 서비스가 하지만
 * <b>결정은 하지 않는다.</b> 결정이 두 군데로 나뉘는 순간 레거시와 같은 모양이 된다.
 *
 * @param userId  소유자
 * @param entries 기본 배송지 먼저, 그 다음 최근 등록 순. 정렬은 저장소가 보장한다
 */
public record AddressBook(Long userId, List<ShippingAddressEntry> entries) {

    /**
     * 한 사용자가 저장할 수 있는 최대 개수.
     *
     * <p>레거시에는 상한이 없었다. 주소록은 목록을 통째로 읽어 화면에 뿌리는 자료라, 상한이 없으면
     * 한 계정이 조회 한 번의 비용을 무한정 키울 수 있다. 사람이 실제로 관리하는 배송지 수를 한참
     * 넘는 값이라 정상 사용에는 걸리지 않는다.
     */
    public static final int MAX_ENTRIES = 30;

    public AddressBook {
        Objects.requireNonNull(userId, "userId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public static AddressBook empty(Long userId) {
        return new AddressBook(userId, List.of());
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 기본 배송지. 주소록이 비어 있을 때만 비어 있다 — 그 외에 비면 불변식이 깨진 것이다. */
    public Optional<ShippingAddressEntry> defaultEntry() {
        return entries.stream().filter(ShippingAddressEntry::defaultAddress).findFirst();
    }

    /** 내 주소록의 줄인가. 남의 줄을 id 로 집어 오는 경로를 여기서 한 번 더 끊는다. */
    public Optional<ShippingAddressEntry> find(Long entryId) {
        if (entryId == null) {
            return Optional.empty();
        }
        return entries.stream().filter(e -> entryId.equals(e.id())).findFirst();
    }

    /** 없으면 거절. 서비스가 매번 {@code orElseThrow} 를 손으로 쓰지 않게 한다. */
    public ShippingAddressEntry require(Long entryId) {
        return find(entryId).orElseThrow(() -> new AddressBookInvariantViolationException(
                "주소록에 없는 배송지입니다."));
    }

    /**
     * 새로 넣는 줄이 기본이 되어야 하는가.
     *
     * <p>첫 줄은 <b>사용자가 요청하지 않아도</b> 기본이다. 그러지 않으면 주소록에 한 줄이 있는데
     * 기본은 없는 상태가 만들어지고, 그건 위에 적은 "0개"와 구분되지 않는다.
     */
    public boolean shouldBecomeDefault(boolean requested) {
        return requested || isEmpty();
    }

    /**
     * {@code entryId} 를 지웠을 때 대신 기본이 될 줄.
     *
     * <p>지우는 것이 기본이 아니면 승계는 일어나지 않으므로 비어서 돌아온다. 기본을 지우는데 남는
     * 줄이 있으면 <b>반드시</b> 하나가 승격해야 한다 — 이 메서드가 비어 있지 않은 값을 돌려주는
     * 유일한 경우다. 고르는 기준은 최근에 등록한 것이고, 등록 시각이 같거나 없으면 id 가 큰 쪽이다
     * (같은 초에 둘을 넣어도 결정이 흔들리지 않게).
     */
    public Optional<ShippingAddressEntry> successorAfterRemoving(Long entryId) {
        ShippingAddressEntry removed = require(entryId);
        if (!removed.defaultAddress()) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(e -> !e.id().equals(entryId))
                .max(Comparator
                        .comparing(ShippingAddressEntry::createdAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ShippingAddressEntry::id));
    }

    /** 저장할 자리가 남았는지. */
    public boolean isFull() {
        return entries.size() >= MAX_ENTRIES;
    }

    /** 상한 검사. 수정은 개수를 늘리지 않으므로 등록 경로에서만 부른다. */
    public void requireRoom() {
        if (isFull()) {
            throw new AddressBookInvariantViolationException(
                    "배송지는 최대 " + MAX_ENTRIES + "개까지 저장할 수 있습니다. 쓰지 않는 배송지를 지운 뒤 다시 시도하세요.");
        }
    }
}
