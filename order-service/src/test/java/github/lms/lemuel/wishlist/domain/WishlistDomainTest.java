package github.lms.lemuel.wishlist.domain;

import github.lms.lemuel.wishlist.domain.exception.WishlistInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("찜 도메인")
class WishlistDomainTest {

    private static WishlistProduct product(Long id, WishlistAvailability availability) {
        return new WishlistProduct(id, "상품 " + id, new BigDecimal("1000"), availability, null);
    }

    private static WishlistEntry entry(Long productId, WishlistAvailability availability) {
        return new WishlistEntry(
                WishlistItem.rehydrate(productId, 1L, productId, LocalDateTime.now()),
                product(productId, availability));
    }

    @Nested
    @DisplayName("WishlistAvailability")
    class Availability {

        @Test
        @DisplayName("살 수 있는 상태는 AVAILABLE 하나뿐이다")
        void onlyAvailableIsBuyable() {
            for (WishlistAvailability a : WishlistAvailability.values()) {
                assertThat(a.isAvailable()).isEqualTo(a == WishlistAvailability.AVAILABLE);
            }
        }

        @Test
        @DisplayName("일괄 정리 대상은 단종·삭제뿐 — 품절은 남긴다")
        void goneIsDiscontinuedOrRemovedOnly() {
            // 재입고를 기다리는 것이 찜의 목적이다. 품절을 정리 대상에 넣으면
            // 사용자가 가장 지키고 싶어 한 줄을 버튼 한 번이 지운다.
            assertThat(WishlistAvailability.OUT_OF_STOCK.isGone()).isFalse();
            assertThat(WishlistAvailability.NOT_SELLING.isGone()).isFalse();
            assertThat(WishlistAvailability.DISCONTINUED.isGone()).isTrue();
            assertThat(WishlistAvailability.REMOVED.isGone()).isTrue();
        }

        @Test
        @DisplayName("모든 상태가 사람이 읽는 사유를 가진다")
        void everyStateHasLabel() {
            for (WishlistAvailability a : WishlistAvailability.values()) {
                assertThat(a.label()).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("WishlistProduct")
    class Product {

        @Test
        @DisplayName("이름이 비면 거부한다")
        void rejectsBlankName() {
            assertThatThrownBy(() -> new WishlistProduct(
                    1L, "  ", BigDecimal.ONE, WishlistAvailability.AVAILABLE, null))
                    .isInstanceOf(WishlistInvariantViolationException.class);
            assertThatThrownBy(() -> new WishlistProduct(
                    1L, null, BigDecimal.ONE, WishlistAvailability.AVAILABLE, null))
                    .isInstanceOf(WishlistInvariantViolationException.class);
        }

        @Test
        @DisplayName("삭제된 상품도 값이다 — null 이 아니라 사유를 들고 온다")
        void removedIsAValueNotNull() {
            WishlistProduct removed = WishlistProduct.removed(9L);

            assertThat(removed.productId()).isEqualTo(9L);
            assertThat(removed.name()).isEqualTo(WishlistProduct.REMOVED_NAME);
            assertThat(removed.price()).isNull();
            assertThat(removed.isAvailable()).isFalse();
            assertThat(removed.isGone()).isTrue();
        }
    }

    @Nested
    @DisplayName("WishlistItem")
    class Item {

        @Test
        @DisplayName("add 는 id 없이 만들어진다 — 번호는 DB 가 발급한다")
        void addLeavesIdToDatabase() {
            WishlistItem item = WishlistItem.add(7L, 3L);

            assertThat(item.id()).isNull();
            assertThat(item.userId()).isEqualTo(7L);
            assertThat(item.productId()).isEqualTo(3L);
            assertThat(item.addedAt()).isNotNull();
        }

        @Test
        @DisplayName("주인과 상품이 없으면 만들 수 없다")
        void requiresOwnerAndProduct() {
            assertThatThrownBy(() -> WishlistItem.add(null, 3L))
                    .isInstanceOf(WishlistInvariantViolationException.class);
            assertThatThrownBy(() -> WishlistItem.add(7L, null))
                    .isInstanceOf(WishlistInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("WishlistEntry")
    class Entry {

        @Test
        @DisplayName("찜 행과 상품이 어긋나면 만들 수 없다")
        void rejectsMismatchedPair() {
            WishlistItem item = WishlistItem.rehydrate(1L, 7L, 100L, LocalDateTime.now());

            assertThatThrownBy(() -> new WishlistEntry(item, product(200L, WishlistAvailability.AVAILABLE)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("사유는 상태의 한글 표기를 그대로 쓴다")
        void reasonComesFromAvailability() {
            assertThat(entry(1L, WishlistAvailability.OUT_OF_STOCK).reason())
                    .isEqualTo(WishlistAvailability.OUT_OF_STOCK.label());
        }
    }

    @Nested
    @DisplayName("Wishlist")
    class Aggregate {

        @Test
        @DisplayName("목록은 거르지 않는다 — 살 수 없는 것도 그대로 들어 있다")
        void keepsUnavailableEntries() {
            Wishlist wishlist = new Wishlist(1L, List.of(
                    entry(10L, WishlistAvailability.AVAILABLE),
                    entry(11L, WishlistAvailability.OUT_OF_STOCK),
                    entry(12L, WishlistAvailability.DISCONTINUED),
                    entry(13L, WishlistAvailability.REMOVED)));

            assertThat(wishlist.size()).isEqualTo(4);
            assertThat(wishlist.available()).extracting(WishlistEntry::productId).containsExactly(10L);
            assertThat(wishlist.gone()).extracting(WishlistEntry::productId).containsExactly(12L, 13L);
        }

        @Test
        @DisplayName("품절은 정리 대상이 아니다")
        void outOfStockSurvivesPurge() {
            Wishlist wishlist = new Wishlist(1L, List.of(entry(11L, WishlistAvailability.OUT_OF_STOCK)));

            assertThat(wishlist.gone()).isEmpty();
        }

        @Test
        @DisplayName("바깥에서 항목 목록을 바꿔도 내부는 흔들리지 않는다")
        void entriesAreCopied() {
            List<WishlistEntry> mutable = new ArrayList<>(List.of(entry(10L, WishlistAvailability.AVAILABLE)));
            Wishlist wishlist = new Wishlist(1L, mutable);

            mutable.clear();

            assertThat(wishlist.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("상한에 닿으면 더 담기를 거부한다")
        void refusesBeyondMaxItems() {
            List<WishlistEntry> full = new ArrayList<>();
            for (long i = 0; i < Wishlist.MAX_ITEMS; i++) {
                full.add(entry(i, WishlistAvailability.AVAILABLE));
            }
            Wishlist wishlist = new Wishlist(1L, full);

            assertThat(wishlist.isFull()).isTrue();
            assertThatThrownBy(wishlist::requireRoom)
                    .isInstanceOf(WishlistInvariantViolationException.class)
                    .hasMessageContaining(String.valueOf(Wishlist.MAX_ITEMS));
        }

        @Test
        @DisplayName("상한 직전까지는 자리가 있다")
        void hasRoomJustBelowMax() {
            List<WishlistEntry> almost = new ArrayList<>();
            for (long i = 0; i < Wishlist.MAX_ITEMS - 1; i++) {
                almost.add(entry(i, WishlistAvailability.AVAILABLE));
            }

            Wishlist wishlist = new Wishlist(1L, almost);

            assertThat(wishlist.isFull()).isFalse();
            wishlist.requireRoom();
        }

        @Test
        @DisplayName("빈 목록은 주인은 있고 항목은 없다")
        void emptyKeepsOwner() {
            Wishlist empty = Wishlist.empty(42L);

            assertThat(empty.userId()).isEqualTo(42L);
            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.contains(1L)).isFalse();
        }

        @Test
        @DisplayName("contains 는 담긴 상품을 찾는다")
        void containsFindsProduct() {
            Wishlist wishlist = new Wishlist(1L, List.of(entry(10L, WishlistAvailability.AVAILABLE)));

            assertThat(wishlist.contains(10L)).isTrue();
            assertThat(wishlist.contains(99L)).isFalse();
        }
    }
}
