package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PointLotSelector 소비 순서 단위 테스트.
 *
 * <p>규칙: <b>만료 임박 순</b>(무기한은 마지막), 동일 만료일이면 로트 id 순.
 * 출처(보너스/현금)로 우선순위를 주지 않는다 — 보너스 우선 소진은 고객에게 불리하게 보이고,
 * 동률 상황이 드물어 얻는 것도 없다.
 */
class PointLotSelectorTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final OffsetDateTime GRANTED_AT =
            OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private PointLot lot(long id, String amount, OffsetDateTime expiresAt) {
        PointLot lot = PointLot.issue(ACCOUNT_ID, PointLotOrigin.ORDER_EARN, new BigDecimal(amount),
                GRANTED_AT, expiresAt, "ORDER", "ref-" + id);
        lot.assignId(id);
        return lot;
    }

    @Test
    @DisplayName("만료가 이른 로트부터 소비한다")
    void consumesEarliestExpiryFirst() {
        PointLot later = lot(1L, "1000", GRANTED_AT.plusDays(60));
        PointLot sooner = lot(2L, "1000", GRANTED_AT.plusDays(10));

        List<PointLotConsumption> plan =
                PointLotSelector.consume(List.of(later, sooner), new BigDecimal("500"));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).lotId()).isEqualTo(2L);
        assertThat(sooner.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(later.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    @DisplayName("한 로트로 모자라면 다음 로트로 이어서 소비한다")
    void spansMultipleLots() {
        PointLot first = lot(1L, "300", GRANTED_AT.plusDays(10));
        PointLot second = lot(2L, "500", GRANTED_AT.plusDays(20));
        PointLot third = lot(3L, "900", GRANTED_AT.plusDays(30));

        List<PointLotConsumption> plan =
                PointLotSelector.consume(List.of(third, first, second), new BigDecimal("1000"));

        assertThat(plan).extracting(PointLotConsumption::lotId).containsExactly(1L, 2L, 3L);
        assertThat(plan).extracting(PointLotConsumption::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300"), new BigDecimal("500"), new BigDecimal("200"));
        assertThat(first.getStatus()).isEqualTo(PointLotStatus.EXHAUSTED);
        assertThat(second.getStatus()).isEqualTo(PointLotStatus.EXHAUSTED);
        assertThat(third.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("700"));
    }

    @Test
    @DisplayName("무기한 로트는 만료가 있는 로트보다 나중에 소비한다")
    void unlimitedLotsGoLast() {
        PointLot unlimited = lot(1L, "1000", null);
        PointLot expiring = lot(2L, "1000", GRANTED_AT.plusDays(365));

        List<PointLotConsumption> plan =
                PointLotSelector.consume(List.of(unlimited, expiring), new BigDecimal("1500"));

        assertThat(plan).extracting(PointLotConsumption::lotId).containsExactly(2L, 1L);
        assertThat(plan.get(0).amount()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(plan.get(1).amount()).isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    @DisplayName("만료일이 같으면 먼저 발급된(id 작은) 로트부터 소비한다")
    void tiesBrokenByLotId() {
        PointLot second = lot(9L, "500", GRANTED_AT.plusDays(30));
        PointLot first = lot(3L, "500", GRANTED_AT.plusDays(30));

        List<PointLotConsumption> plan =
                PointLotSelector.consume(List.of(second, first), new BigDecimal("600"));

        assertThat(plan).extracting(PointLotConsumption::lotId).containsExactly(3L, 9L);
    }

    @Test
    @DisplayName("가용 합계보다 1원이라도 많으면 거절하고 로트를 건드리지 않는다 (경계값)")
    void rejectsOverTotalWithoutMutating() {
        PointLot first = lot(1L, "300", GRANTED_AT.plusDays(10));
        PointLot second = lot(2L, "700", GRANTED_AT.plusDays(20));

        assertThatThrownBy(() ->
                PointLotSelector.consume(List.of(first, second), new BigDecimal("1001")))
                .isInstanceOf(InsufficientPointException.class);

        assertThat(first.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(second.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("700"));
        assertThat(first.getStatus()).isEqualTo(PointLotStatus.ACTIVE);
    }

    @Test
    @DisplayName("가용 합계와 정확히 같은 요청은 전부 소비한다 (경계값)")
    void consumesExactTotal() {
        PointLot first = lot(1L, "300", GRANTED_AT.plusDays(10));
        PointLot second = lot(2L, "700", GRANTED_AT.plusDays(20));

        List<PointLotConsumption> plan =
                PointLotSelector.consume(List.of(first, second), new BigDecimal("1000"));

        assertThat(plan).hasSize(2);
        assertThat(first.getStatus()).isEqualTo(PointLotStatus.EXHAUSTED);
        assertThat(second.getStatus()).isEqualTo(PointLotStatus.EXHAUSTED);
    }

    @Test
    @DisplayName("소진·소멸된 로트는 재원에서 제외한다")
    void skipsNonConsumableLots() {
        PointLot exhausted = lot(1L, "1000", GRANTED_AT.plusDays(10));
        exhausted.consume(new BigDecimal("1000"));
        PointLot usable = lot(2L, "500", GRANTED_AT.plusDays(20));

        List<PointLotConsumption> plan =
                PointLotSelector.consume(List.of(exhausted, usable), new BigDecimal("500"));

        assertThat(plan).extracting(PointLotConsumption::lotId).containsExactly(2L);
    }

    @Test
    @DisplayName("재원이 하나도 없으면 잔액 부족으로 거절한다")
    void rejectsWhenNoLots() {
        assertThatThrownBy(() -> PointLotSelector.consume(List.of(), new BigDecimal("100")))
                .isInstanceOf(InsufficientPointException.class);
    }
}
