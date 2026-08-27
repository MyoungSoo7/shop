package github.lms.lemuel.point.domain;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.point.domain.exception.PointTransferRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("회원 간 포인트 선물 도메인")
class PointTransferDomainTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-08-28T10:00:00+09:00");
    private static final String NO = "PT20260828-00000001";
    private static final String KEY = "req-1";

    private static PointTransfer transfer(Long sender, Long receiver, BigDecimal amount, String message) {
        return PointTransfer.create(NO, KEY, sender, receiver, amount, message, T0);
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("정상 생성은 값을 그대로 담고 아직 ID 가 없다")
        void createsWithoutId() {
            PointTransfer transfer = transfer(1L, 2L, new BigDecimal("1000"), "고마워");

            assertThat(transfer.getId()).isNull();
            assertThat(transfer.getTransferNo()).isEqualTo(NO);
            assertThat(transfer.getRequestId()).isEqualTo(KEY);
            assertThat(transfer.getSenderUserId()).isEqualTo(1L);
            assertThat(transfer.getReceiverUserId()).isEqualTo(2L);
            assertThat(transfer.getAmount()).isEqualByComparingTo("1000");
            assertThat(transfer.getMessage()).isEqualTo("고마워");
            assertThat(transfer.getCreatedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("자기 자신에게는 보낼 수 없다 — 레거시에서 유효기간 초기화 수단이었다")
        void rejectsSelfTransfer() {
            assertThatThrownBy(() -> transfer(7L, 7L, new BigDecimal("1000"), null))
                    .isInstanceOf(PointTransferRejectedException.class)
                    .extracting(e -> ((PointTransferRejectedException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_TRANSFER_SELF);
        }

        @Test
        @DisplayName("0 이나 음수는 보낼 수 없다")
        void rejectsNonPositiveAmount() {
            assertThatThrownBy(() -> transfer(1L, 2L, BigDecimal.ZERO, null))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> transfer(1L, 2L, new BigDecimal("-1"), null))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("원 미만 단위는 보낼 수 없다 — 어딘가에서 나눈 값이 흘러온 것이다")
        void rejectsFractionalAmount() {
            assertThatThrownBy(() -> transfer(1L, 2L, new BigDecimal("100.5"), null))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("빈 메시지는 null 로 정규화한다 — 화면이 둘 다 '메시지 없음'으로 그린다")
        void normalizesBlankMessage() {
            assertThat(transfer(1L, 2L, new BigDecimal("100"), "   ").getMessage()).isNull();
            assertThat(transfer(1L, 2L, new BigDecimal("100"), null).getMessage()).isNull();
        }

        @Test
        @DisplayName("메시지 앞뒤 공백은 걷어 낸다")
        void stripsMessage() {
            assertThat(transfer(1L, 2L, new BigDecimal("100"), "  잘 써  ").getMessage())
                    .isEqualTo("잘 써");
        }

        @Test
        @DisplayName("메시지가 200자를 넘으면 거절한다 — DB 칼럼 길이와 같은 값이다")
        void rejectsOverlongMessage() {
            String tooLong = "가".repeat(PointTransfer.MAX_MESSAGE_LENGTH + 1);
            assertThatThrownBy(() -> transfer(1L, 2L, new BigDecimal("100"), tooLong))
                    .isInstanceOf(PointTransferRejectedException.class)
                    .extracting(e -> ((PointTransferRejectedException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_INVALID_STATE);
        }

        @Test
        @DisplayName("선물 번호나 요청 식별자가 비면 거절한다")
        void rejectsMissingIdentifiers() {
            assertThatThrownBy(() -> PointTransfer.create("  ", KEY, 1L, 2L,
                    new BigDecimal("100"), null, T0))
                    .isInstanceOf(PointTransferRejectedException.class);
            assertThatThrownBy(() -> PointTransfer.create(NO, "  ", 1L, 2L,
                    new BigDecimal("100"), null, T0))
                    .isInstanceOf(PointTransferRejectedException.class);
        }

        @Test
        @DisplayName("요청 식별자가 64자를 넘으면 거절한다")
        void rejectsOverlongRequestId() {
            String tooLong = "a".repeat(PointTransfer.MAX_REQUEST_ID_LENGTH + 1);
            assertThatThrownBy(() -> PointTransfer.create(NO, tooLong, 1L, 2L,
                    new BigDecimal("100"), null, T0))
                    .isInstanceOf(PointTransferRejectedException.class);
        }

        @Test
        @DisplayName("보내는 이·받는 이·시각이 비면 거절한다")
        void rejectsMissingParticipants() {
            assertThatThrownBy(() -> transfer(null, 2L, new BigDecimal("100"), null))
                    .isInstanceOf(PointTransferRejectedException.class);
            assertThatThrownBy(() -> transfer(1L, null, new BigDecimal("100"), null))
                    .isInstanceOf(PointTransferRejectedException.class);
            assertThatThrownBy(() -> PointTransfer.create(NO, KEY, 1L, 2L,
                    new BigDecimal("100"), null, null))
                    .isInstanceOf(PointTransferRejectedException.class);
        }
    }

    @Nested
    @DisplayName("방향과 상대방")
    class Direction {

        @Test
        @DisplayName("보낸 이에게는 보낸 것, 받은 이에게는 받은 것이다")
        void resolvesDirection() {
            PointTransfer transfer = transfer(1L, 2L, new BigDecimal("100"), null);

            assertThat(transfer.isOutgoingFor(1L)).isTrue();
            assertThat(transfer.isOutgoingFor(2L)).isFalse();
        }

        @Test
        @DisplayName("상대방은 나 아닌 쪽이다")
        void resolvesCounterpart() {
            PointTransfer transfer = transfer(1L, 2L, new BigDecimal("100"), null);

            assertThat(transfer.counterpartOf(1L)).isEqualTo(2L);
            assertThat(transfer.counterpartOf(2L)).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("식별자 할당")
    class AssignId {

        @Test
        @DisplayName("한 번만 할당된다 — 기록을 다른 행에 덧씌우는 경로를 막는다")
        void assignsOnce() {
            PointTransfer transfer = transfer(1L, 2L, new BigDecimal("100"), null);
            transfer.assignId(10L);

            assertThat(transfer.getId()).isEqualTo(10L);
            assertThatThrownBy(() -> transfer.assignId(11L))
                    .isInstanceOf(PointTransferRejectedException.class);
        }
    }

    @Nested
    @DisplayName("복원")
    class Rehydrate {

        @Test
        @DisplayName("저장된 값을 그대로 되살린다")
        void rehydrates() {
            PointTransfer transfer = PointTransfer.rehydrate(5L, NO, KEY, 1L, 2L,
                    new BigDecimal("1000.00"), "고마워", T0);

            assertThat(transfer.getId()).isEqualTo(5L);
            assertThat(transfer.getAmount()).isEqualByComparingTo("1000");
            assertThat(transfer.isOutgoingFor(1L)).isTrue();
        }
    }
}
