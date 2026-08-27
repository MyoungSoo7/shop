package github.lms.lemuel.addressbook.application.service;

import github.lms.lemuel.addressbook.application.port.in.AddressBookUseCase.AddressForm;
import github.lms.lemuel.addressbook.application.port.out.LoadAddressBookPort;
import github.lms.lemuel.addressbook.application.port.out.SaveAddressBookPort;
import github.lms.lemuel.addressbook.domain.AddressBook;
import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;
import github.lms.lemuel.addressbook.domain.exception.AddressBookInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("배송지 주소록 서비스")
class AddressBookServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 27, 10, 0, 0);

    @Mock private LoadAddressBookPort loadAddressBookPort;
    @Mock private SaveAddressBookPort saveAddressBookPort;

    @InjectMocks private AddressBookService service;

    private static ShippingAddressEntry entry(long id, String label, boolean isDefault,
                                              LocalDateTime createdAt) {
        return new ShippingAddressEntry(id, USER_ID, label, "홍길동", "010-1234-5678",
                "06236", "서울 강남구 테헤란로 1", "301호", null, isDefault, createdAt, createdAt);
    }

    private static ShippingAddressEntry entry(long id, String label, boolean isDefault) {
        return entry(id, label, isDefault, T0);
    }

    private static AddressForm form(String label, boolean makeDefault) {
        return new AddressForm(label, "홍길동", "010-1234-5678", "06236",
                "서울 강남구 테헤란로 1", "301호", null, makeDefault);
    }

    private void bookContains(ShippingAddressEntry... entries) {
        when(loadAddressBookPort.findByUserId(USER_ID)).thenReturn(List.of(entries));
    }

    private void savesArgument() {
        when(saveAddressBookPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("등록")
    class Register {

        @Test
        @DisplayName("첫 배송지는 요청하지 않아도 기본이 된다")
        void firstAddressBecomesDefault() {
            bookContains();
            savesArgument();

            ShippingAddressEntry saved = service.register(USER_ID, form("집", false));

            assertThat(saved.defaultAddress()).isTrue();
        }

        @Test
        @DisplayName("두 번째 배송지는 요청하지 않으면 기본이 아니고, 기본을 내리지도 않는다")
        void secondAddressLeavesDefaultAlone() {
            bookContains(entry(1L, "집", true));
            savesArgument();

            ShippingAddressEntry saved = service.register(USER_ID, form("회사", false));

            assertThat(saved.defaultAddress()).isFalse();
            verify(saveAddressBookPort, never()).clearDefault(anyLong());
        }

        @Test
        @DisplayName("기본으로 등록하면 내리기가 저장보다 먼저다 — 순서가 뒤집히면 유일 인덱스가 거부한다")
        void clearsBeforeSavingNewDefault() {
            bookContains(entry(1L, "집", true));
            savesArgument();

            service.register(USER_ID, form("회사", true));

            InOrder order = inOrder(saveAddressBookPort);
            order.verify(saveAddressBookPort).clearDefault(USER_ID);
            order.verify(saveAddressBookPort).save(any());
        }

        @Test
        @DisplayName("별칭과 받는 사람 이름을 각각 그대로 저장한다")
        void keepsLabelAndRecipientDistinct() {
            bookContains();
            savesArgument();

            service.register(USER_ID, new AddressForm("회사", "김철수", "010-0000-0000",
                    "06236", "서울 강남구", null, null, false));

            ArgumentCaptor<ShippingAddressEntry> captor =
                    ArgumentCaptor.forClass(ShippingAddressEntry.class);
            verify(saveAddressBookPort).save(captor.capture());
            assertThat(captor.getValue().label()).isEqualTo("회사");
            assertThat(captor.getValue().recipientName()).isEqualTo("김철수");
        }

        @Test
        @DisplayName("상한을 넘으면 저장하지 않고 거절한다")
        void rejectsBeyondCap() {
            List<ShippingAddressEntry> full = new ArrayList<>();
            for (int i = 1; i <= AddressBook.MAX_ENTRIES; i++) {
                full.add(entry(i, "주소 " + i, i == 1));
            }
            when(loadAddressBookPort.findByUserId(USER_ID)).thenReturn(full);

            assertThatThrownBy(() -> service.register(USER_ID, form("하나 더", false)))
                    .isInstanceOf(AddressBookInvariantViolationException.class);

            verify(saveAddressBookPort, never()).save(any());
        }

        @Test
        @DisplayName("userId 가 없으면 조회조차 하지 않는다")
        void rejectsMissingUser() {
            assertThatThrownBy(() -> service.register(null, form("집", false)))
                    .isInstanceOf(AddressBookInvariantViolationException.class);

            verify(loadAddressBookPort, never()).findByUserId(any());
        }
    }

    @Nested
    @DisplayName("수정")
    class Modify {

        @Test
        @DisplayName("내용만 바꾸면 기본 여부는 건드리지 않는다")
        void contentOnlyEditKeepsDefaultFlag() {
            bookContains(entry(1L, "집", true));
            savesArgument();

            ShippingAddressEntry saved = service.modify(USER_ID, 1L, form("우리집", false));

            assertThat(saved.label()).isEqualTo("우리집");
            assertThat(saved.defaultAddress()).isTrue();
            verify(saveAddressBookPort, never()).clearDefault(anyLong());
        }

        @Test
        @DisplayName("이미 기본인 줄을 다시 기본으로 지정해도 내리지 않는다")
        void reDefaultingTheCurrentDefaultIsANoOp() {
            // 내려 버리면 그 트랜잭션 안에 기본이 0개인 순간이 생기고, 저장이 실패하면 그대로 남는다.
            bookContains(entry(1L, "집", true));
            savesArgument();

            service.modify(USER_ID, 1L, form("우리집", true));

            verify(saveAddressBookPort, never()).clearDefault(anyLong());
        }

        @Test
        @DisplayName("다른 줄을 기본으로 올릴 때는 내리기가 먼저다")
        void promotingAnotherClearsFirst() {
            bookContains(entry(1L, "집", true), entry(2L, "회사", false));
            savesArgument();

            ShippingAddressEntry saved = service.modify(USER_ID, 2L, form("회사", true));

            assertThat(saved.defaultAddress()).isTrue();
            InOrder order = inOrder(saveAddressBookPort);
            order.verify(saveAddressBookPort).clearDefault(USER_ID);
            order.verify(saveAddressBookPort).save(any());
        }

        @Test
        @DisplayName("남의 배송지 id 는 '주소록에 없는 배송지'로 거절된다")
        void foreignIdIsRejected() {
            bookContains(entry(1L, "집", true));

            assertThatThrownBy(() -> service.modify(USER_ID, 99L, form("남의집", false)))
                    .isInstanceOf(AddressBookInvariantViolationException.class);

            verify(saveAddressBookPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("기본 지정")
    class SetDefault {

        @Test
        @DisplayName("내리고 올리는 두 문장이 이 순서로 나간다")
        void clearThenMark() {
            bookContains(entry(1L, "집", true), entry(2L, "회사", false));

            service.setDefault(USER_ID, 2L);

            InOrder order = inOrder(saveAddressBookPort);
            order.verify(saveAddressBookPort).clearDefault(USER_ID);
            order.verify(saveAddressBookPort).markDefault(2L);
        }

        @Test
        @DisplayName("이미 기본이면 아무 문장도 나가지 않는다")
        void alreadyDefaultWritesNothing() {
            bookContains(entry(1L, "집", true));

            service.setDefault(USER_ID, 1L);

            verify(saveAddressBookPort, never()).clearDefault(anyLong());
            verify(saveAddressBookPort, never()).markDefault(anyLong());
        }

        @Test
        @DisplayName("없는 id 는 거절하고 아무것도 쓰지 않는다")
        void unknownIdWritesNothing() {
            bookContains(entry(1L, "집", true));

            assertThatThrownBy(() -> service.setDefault(USER_ID, 99L))
                    .isInstanceOf(AddressBookInvariantViolationException.class);

            verify(saveAddressBookPort, never()).clearDefault(anyLong());
            verify(saveAddressBookPort, never()).markDefault(anyLong());
        }
    }

    @Nested
    @DisplayName("삭제")
    class Remove {

        @Test
        @DisplayName("기본을 지우면 남은 것 중 하나가 곧바로 승격한다")
        void deletingDefaultPromotesSuccessor() {
            bookContains(entry(1L, "집", true, T0.plusDays(2)),
                    entry(2L, "회사", false, T0.plusDays(1)));

            service.remove(USER_ID, 1L);

            InOrder order = inOrder(saveAddressBookPort);
            order.verify(saveAddressBookPort).deleteById(1L);
            order.verify(saveAddressBookPort).markDefault(2L);
        }

        @Test
        @DisplayName("기본이 아닌 줄을 지우면 승격은 일어나지 않는다")
        void deletingNonDefaultPromotesNobody() {
            bookContains(entry(1L, "집", true), entry(2L, "회사", false));

            service.remove(USER_ID, 2L);

            verify(saveAddressBookPort).deleteById(2L);
            verify(saveAddressBookPort, never()).markDefault(anyLong());
        }

        @Test
        @DisplayName("마지막 줄을 지우면 빈 주소록이 된다 — 승격 대상이 없다")
        void deletingLastLeavesEmptyBook() {
            bookContains(entry(1L, "집", true));

            service.remove(USER_ID, 1L);

            verify(saveAddressBookPort).deleteById(1L);
            verify(saveAddressBookPort, never()).markDefault(anyLong());
        }

        @Test
        @DisplayName("남의 배송지 id 는 지워지지 않는다")
        void foreignIdIsNotDeleted() {
            bookContains(entry(1L, "집", true));

            assertThatThrownBy(() -> service.remove(USER_ID, 99L))
                    .isInstanceOf(AddressBookInvariantViolationException.class);

            verify(saveAddressBookPort, never()).deleteById(anyLong());
        }
    }

    @Nested
    @DisplayName("조회")
    class Query {

        @Test
        @DisplayName("목록은 저장소가 준 순서를 그대로 유지한다 — 기본이 맨 위")
        void listKeepsRepositoryOrder() {
            bookContains(entry(1L, "집", true), entry(2L, "회사", false));

            AddressBook book = service.list(USER_ID);

            assertThat(book.entries()).extracting(ShippingAddressEntry::id).containsExactly(1L, 2L);
            assertThat(book.defaultEntry().orElseThrow().id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("빈 주소록에서는 기본 배송지가 비어서 돌아온다")
        void emptyBookHasNoDefault() {
            bookContains();

            assertThat(service.findDefault(USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("기본 배송지 단건 조회는 목록의 기본과 같은 줄이다")
        void findDefaultMatchesList() {
            bookContains(entry(1L, "집", true), entry(2L, "회사", false));

            assertThat(service.findDefault(USER_ID))
                    .get()
                    .extracting(ShippingAddressEntry::id)
                    .isEqualTo(1L);
        }
    }
}
