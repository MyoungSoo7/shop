package github.lms.lemuel.addressbook.domain;

import github.lms.lemuel.addressbook.domain.exception.AddressBookInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("배송지 주소록 도메인")
class AddressBookDomainTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 27, 10, 0, 0);

    private static ShippingAddressEntry entry(long id, String label, boolean isDefault,
                                              LocalDateTime createdAt) {
        return new ShippingAddressEntry(id, USER_ID, label, "홍길동", "010-1234-5678",
                "06236", "서울 강남구 테헤란로 1", "301호", null, isDefault, createdAt, createdAt);
    }

    private static ShippingAddressEntry entry(long id, String label, boolean isDefault) {
        return entry(id, label, isDefault, T0);
    }

    @Nested
    @DisplayName("ShippingAddressEntry")
    class Entry {

        @Test
        @DisplayName("별칭과 받는 사람 이름은 서로 다른 칸이며 서로를 채워 주지 않는다")
        void labelAndRecipientAreDistinct() {
            // 레거시는 등록 SQL 이 별칭 자리에 받는 사람 이름을 넣어, 한 번 수정하기 전까지
            // 별칭이 이름의 사본이었다. 여기서는 넣은 값이 그대로 각자 남는다.
            ShippingAddressEntry e = ShippingAddressEntry.draft(USER_ID, "회사", "김철수",
                    "010-0000-0000", "06236", "서울 강남구", null, null);

            assertThat(e.label()).isEqualTo("회사");
            assertThat(e.recipientName()).isEqualTo("김철수");
        }

        @Test
        @DisplayName("필수 항목이 비면 거절한다 — 어느 칸인지 메시지에 담는다")
        void rejectsBlankRequiredFields() {
            assertThatThrownBy(() -> ShippingAddressEntry.draft(USER_ID, "  ", "김철수",
                    "010-0000-0000", "06236", "서울 강남구", null, null))
                    .isInstanceOf(AddressBookInvariantViolationException.class)
                    .hasMessageContaining("배송지 별칭");

            assertThatThrownBy(() -> ShippingAddressEntry.draft(USER_ID, "회사", null,
                    "010-0000-0000", "06236", "서울 강남구", null, null))
                    .isInstanceOf(AddressBookInvariantViolationException.class)
                    .hasMessageContaining("받는 분");

            assertThatThrownBy(() -> ShippingAddressEntry.draft(USER_ID, "회사", "김철수",
                    "010-0000-0000", "06236", "", null, null))
                    .isInstanceOf(AddressBookInvariantViolationException.class)
                    .hasMessageContaining("주소");
        }

        @Test
        @DisplayName("별칭이 상한을 넘으면 DB 가 자르기 전에 도메인이 거절한다")
        void rejectsOverlongLabel() {
            String tooLong = "가".repeat(ShippingAddressEntry.MAX_LABEL_LENGTH + 1);

            assertThatThrownBy(() -> ShippingAddressEntry.draft(USER_ID, tooLong, "김철수",
                    "010-0000-0000", "06236", "서울 강남구", null, null))
                    .isInstanceOf(AddressBookInvariantViolationException.class)
                    .hasMessageContaining(String.valueOf(ShippingAddressEntry.MAX_LABEL_LENGTH));
        }

        @Test
        @DisplayName("선택 항목의 빈 문자열은 null 로 눕힌다")
        void blankOptionalsBecomeNull() {
            // "" 과 null 이 뒤섞이면 화면이 두 가지를 각각 다뤄야 한다. 뜻이 같으므로 하나로 모은다.
            ShippingAddressEntry e = ShippingAddressEntry.draft(USER_ID, "집", "홍길동",
                    "010-0000-0000", "06236", "서울 강남구", "   ", "");

            assertThat(e.address2()).isNull();
            assertThat(e.deliveryMemo()).isNull();
        }

        @Test
        @DisplayName("draft 는 기본 배송지가 아니다 — 기본 여부는 주소록이 정한다")
        void draftIsNeverDefault() {
            assertThat(ShippingAddressEntry.draft(USER_ID, "집", "홍길동",
                    "010-0000-0000", "06236", "서울 강남구", null, null).defaultAddress()).isFalse();
        }

        @Test
        @DisplayName("withContent 는 소유자·식별자·기본 여부를 바꾸지 않는다")
        void withContentKeepsIdentityAndDefaultFlag() {
            ShippingAddressEntry original = entry(1L, "집", true);

            ShippingAddressEntry updated = original.withContent("회사", "김철수", "010-9999-9999",
                    "13529", "경기 성남시", "8층", "문 앞에");

            assertThat(updated.id()).isEqualTo(1L);
            assertThat(updated.userId()).isEqualTo(USER_ID);
            assertThat(updated.defaultAddress()).isTrue();
            assertThat(updated.label()).isEqualTo("회사");
            assertThat(updated.address1()).isEqualTo("경기 성남시");
        }
    }

    @Nested
    @DisplayName("기본 배송지 규칙")
    class DefaultRules {

        @Test
        @DisplayName("첫 줄은 요청하지 않아도 기본이 된다")
        void firstEntryBecomesDefaultEvenIfNotRequested() {
            // 그러지 않으면 "줄은 있는데 기본은 없는" 상태가 생기고, 그건 기본이 0개인 것과
            // 구분되지 않는다. 레거시에서 기본 배송지 조회가 아무것도 못 돌려주던 상태다.
            assertThat(AddressBook.empty(USER_ID).shouldBecomeDefault(false)).isTrue();
        }

        @Test
        @DisplayName("이미 줄이 있으면 요청했을 때만 기본이 된다")
        void laterEntryBecomesDefaultOnlyOnRequest() {
            AddressBook book = new AddressBook(USER_ID, List.of(entry(1L, "집", true)));

            assertThat(book.shouldBecomeDefault(false)).isFalse();
            assertThat(book.shouldBecomeDefault(true)).isTrue();
        }

        @Test
        @DisplayName("비어 있지 않은 주소록의 기본 배송지는 정확히 하나다")
        void nonEmptyBookHasExactlyOneDefault() {
            AddressBook book = new AddressBook(USER_ID, List.of(
                    entry(1L, "집", true), entry(2L, "회사", false)));

            assertThat(book.defaultEntry()).isPresent();
            assertThat(book.defaultEntry().orElseThrow().id()).isEqualTo(1L);
            assertThat(book.entries()).filteredOn(ShippingAddressEntry::defaultAddress).hasSize(1);
        }

        @Test
        @DisplayName("빈 주소록에서만 기본 배송지가 없다")
        void emptyBookHasNoDefault() {
            assertThat(AddressBook.empty(USER_ID).defaultEntry()).isEmpty();
        }
    }

    @Nested
    @DisplayName("삭제 시 승계")
    class Succession {

        @Test
        @DisplayName("기본이 아닌 줄을 지우면 승계는 일어나지 않는다")
        void removingNonDefaultPromotesNobody() {
            AddressBook book = new AddressBook(USER_ID, List.of(
                    entry(1L, "집", true), entry(2L, "회사", false)));

            assertThat(book.successorAfterRemoving(2L)).isEmpty();
        }

        @Test
        @DisplayName("기본을 지우면 남은 것 중 가장 최근 줄이 승격한다")
        void removingDefaultPromotesNewestRemaining() {
            AddressBook book = new AddressBook(USER_ID, List.of(
                    entry(1L, "집", true, T0.plusDays(3)),
                    entry(2L, "회사", false, T0.plusDays(1)),
                    entry(3L, "부모님댁", false, T0.plusDays(2))));

            assertThat(book.successorAfterRemoving(1L))
                    .get()
                    .extracting(ShippingAddressEntry::id)
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("등록 시각이 같으면 id 가 큰 쪽이 승격한다 — 결정이 흔들리지 않는다")
        void tiesBreakOnId() {
            // 같은 초에 둘을 넣어도 승계 대상이 매번 달라지면 안 된다.
            AddressBook book = new AddressBook(USER_ID, List.of(
                    entry(1L, "집", true, T0),
                    entry(2L, "회사", false, T0),
                    entry(3L, "부모님댁", false, T0)));

            assertThat(book.successorAfterRemoving(1L))
                    .get()
                    .extracting(ShippingAddressEntry::id)
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("마지막 한 줄을 지우면 승계 대상이 없다 — 빈 주소록은 정상이다")
        void removingLastEntryLeavesNoSuccessor() {
            AddressBook book = new AddressBook(USER_ID, List.of(entry(1L, "집", true)));

            assertThat(book.successorAfterRemoving(1L)).isEmpty();
        }

        @Test
        @DisplayName("없는 줄의 승계를 물으면 거절한다")
        void unknownEntryIsRejected() {
            AddressBook book = new AddressBook(USER_ID, List.of(entry(1L, "집", true)));

            assertThatThrownBy(() -> book.successorAfterRemoving(99L))
                    .isInstanceOf(AddressBookInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("소유 대조와 상한")
    class OwnershipAndCap {

        @Test
        @DisplayName("내 주소록에 없는 id 는 '없는 배송지'다 — 남의 줄과 구분되지 않는다")
        void foreignIdIsIndistinguishableFromMissing() {
            // 조회 포트가 이 사용자의 줄만 돌려주므로, 여기서 못 찾았다는 사실이 곧 소유 대조다.
            AddressBook book = new AddressBook(USER_ID, List.of(entry(1L, "집", true)));

            assertThat(book.find(2L)).isEmpty();
            assertThatThrownBy(() -> book.require(2L))
                    .isInstanceOf(AddressBookInvariantViolationException.class)
                    .hasMessageContaining("주소록에 없는");
        }

        @Test
        @DisplayName("id 가 null 이어도 터지지 않고 '없음'으로 답한다")
        void nullIdIsMissingNotCrash() {
            assertThat(AddressBook.empty(USER_ID).find(null)).isEmpty();
        }

        @Test
        @DisplayName("상한까지는 자리가 있고, 상한에서 거절한다")
        void capIsEnforcedAtTheLimit() {
            List<ShippingAddressEntry> entries = new ArrayList<>();
            for (int i = 1; i < AddressBook.MAX_ENTRIES; i++) {
                entries.add(entry(i, "주소 " + i, i == 1));
            }
            AddressBook nearlyFull = new AddressBook(USER_ID, entries);
            assertThat(nearlyFull.isFull()).isFalse();
            nearlyFull.requireRoom();

            entries.add(entry(AddressBook.MAX_ENTRIES, "마지막", false));
            AddressBook full = new AddressBook(USER_ID, entries);

            assertThat(full.isFull()).isTrue();
            assertThatThrownBy(full::requireRoom)
                    .isInstanceOf(AddressBookInvariantViolationException.class)
                    .hasMessageContaining(String.valueOf(AddressBook.MAX_ENTRIES));
        }

        @Test
        @DisplayName("목록은 방어 복사된다 — 밖에서 넘긴 리스트를 고쳐도 주소록은 그대로다")
        void entriesAreDefensivelyCopied() {
            List<ShippingAddressEntry> mutable = new ArrayList<>(List.of(entry(1L, "집", true)));
            AddressBook book = new AddressBook(USER_ID, mutable);

            mutable.clear();

            assertThat(book.size()).isEqualTo(1);
        }
    }
}
