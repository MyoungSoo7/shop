package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointAmountException;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PointEntry 단위 테스트.
 *
 * <p>원장 엔트리의 핵심 불변식은 하나다 — <b>엔트리 금액 = 로트 배분 합계</b>.
 * 이게 깨지면 잔고 요약과 로트 상세가 어긋나고, 그 순간 장부는 설명력을 잃는다.
 */
class PointEntryTest {

    private static final Long ACCOUNT_ID = 7L;

    @Test
    @DisplayName("적립 엔트리는 GRANT 유형이고 잔고를 늘린다")
    void grant_isIncreasing() {
        PointEntry entry = PointEntry.grant(ACCOUNT_ID, new BigDecimal("1000"), "ORDER", "1001", 0,
                List.of(new PointLotConsumption(11L, new BigDecimal("1000"))), "system", null);

        assertThat(entry.getType()).isEqualTo(PointEntryType.GRANT);
        assertThat(entry.getType().increasesBalance()).isTrue();
        assertThat(entry.getAmount()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(entry.getAllocations()).hasSize(1);
    }

    @Test
    @DisplayName("사용 엔트리는 여러 로트 배분을 담을 수 있다")
    void use_spansMultipleLots() {
        PointEntry entry = PointEntry.use(ACCOUNT_ID, new BigDecimal("1000"), "PAYMENT_TENDER", "55", 0,
                List.of(new PointLotConsumption(11L, new BigDecimal("300")),
                        new PointLotConsumption(12L, new BigDecimal("700"))), "user:42");

        assertThat(entry.getType()).isEqualTo(PointEntryType.USE);
        assertThat(entry.getType().increasesBalance()).isFalse();
        assertThat(entry.getAllocations()).hasSize(2);
    }

    @Test
    @DisplayName("배분 합계가 엔트리 금액과 다르면 불변식 위반이다")
    void allocationsMustSumToAmount() {
        assertThatThrownBy(() -> PointEntry.use(ACCOUNT_ID, new BigDecimal("1000"), "PAYMENT_TENDER", "55", 0,
                List.of(new PointLotConsumption(11L, new BigDecimal("300"))), "user:42"))
                .isInstanceOf(PointInvariantViolationException.class);
    }

    @Test
    @DisplayName("배분이 비어 있으면 불변식 위반이다 — 어느 로트를 건드렸는지 알 수 없는 엔트리는 만들지 않는다")
    void allocationsCannotBeEmpty() {
        assertThatThrownBy(() -> PointEntry.expire(ACCOUNT_ID, new BigDecimal("500"), "LOT", "11", 0,
                List.of(), "batch"))
                .isInstanceOf(PointInvariantViolationException.class);
    }

    @Test
    @DisplayName("0원·음수 엔트리는 거절한다 — 방향은 유형이 정하고 금액은 언제나 양수다")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> PointEntry.restore(ACCOUNT_ID, BigDecimal.ZERO, "REFUND", "55", 0,
                List.of(new PointLotConsumption(11L, BigDecimal.ZERO)), "system"))
                .isInstanceOf(InvalidPointAmountException.class);
    }

    @Test
    @DisplayName("소멸·취소 엔트리는 잔고를 줄이고 로트를 소비한다")
    void expireAndRevokeAreDecreasing() {
        PointEntry expired = PointEntry.expire(ACCOUNT_ID, new BigDecimal("500"), "LOT", "11", 0,
                List.of(new PointLotConsumption(11L, new BigDecimal("500"))), "batch");
        PointEntry revoked = PointEntry.revoke(ACCOUNT_ID, new BigDecimal("300"), "ORDER", "1001", 0,
                List.of(new PointLotConsumption(12L, new BigDecimal("300"))), "system");

        assertThat(expired.getType().increasesBalance()).isFalse();
        assertThat(expired.getType().consumesLots()).isTrue();
        assertThat(revoked.getType()).isEqualTo(PointEntryType.REVOKE);
    }

    @Test
    @DisplayName("동일 참조의 재기록은 sequence 로 구분한다 — 같은 tender 의 분할 환불")
    void sequenceDistinguishesRepeatedReference() {
        PointEntry first = PointEntry.restore(ACCOUNT_ID, new BigDecimal("300"), "PAYMENT_TENDER_REFUND", "55", 0,
                List.of(new PointLotConsumption(11L, new BigDecimal("300"))), "system");
        PointEntry second = PointEntry.restore(ACCOUNT_ID, new BigDecimal("200"), "PAYMENT_TENDER_REFUND", "55", 1,
                List.of(new PointLotConsumption(11L, new BigDecimal("200"))), "system");

        assertThat(first.getSequence()).isZero();
        assertThat(second.getSequence()).isEqualTo(1);
        assertThat(first.getReferenceId()).isEqualTo(second.getReferenceId());
    }

    @Test
    @DisplayName("메모는 수기 지급 근거로 보존된다")
    void memoIsPreserved() {
        PointEntry entry = PointEntry.grant(ACCOUNT_ID, new BigDecimal("1000"), "MANUAL", "op-1", 0,
                List.of(new PointLotConsumption(11L, new BigDecimal("1000"))), "admin:1", "CS 보상");

        assertThat(entry.getMemo()).isEqualTo("CS 보상");
        assertThat(entry.getCreatedBy()).isEqualTo("admin:1");
    }

    @Test
    @DisplayName("수기 차감은 REVOKE 로 기록되고 사유가 함께 남는다 — 사라진 돈도 설명돼야 한다")
    void manualDeductIsRevokeWithMemo() {
        PointEntry entry = PointEntry.manualDeduct(ACCOUNT_ID, new BigDecimal("500"), "op-9", 0,
                List.of(new PointLotConsumption(11L, new BigDecimal("500"))), "admin:1", "오지급 회수");

        assertThat(entry.getType()).isEqualTo(PointEntryType.REVOKE);
        assertThat(entry.getReferenceType()).isEqualTo("MANUAL");
        assertThat(entry.getReferenceId()).isEqualTo("op-9");
        assertThat(entry.getMemo()).isEqualTo("오지급 회수");
    }

    @Test
    @DisplayName("수기 차감에 사유가 없으면 거절한다 — 근거 없는 감액은 만들 수 없다")
    void manualDeductRequiresMemo() {
        List<PointLotConsumption> allocations =
                List.of(new PointLotConsumption(11L, new BigDecimal("500")));

        assertThatThrownBy(() -> PointEntry.manualDeduct(ACCOUNT_ID, new BigDecimal("500"), "op-9", 0,
                allocations, "admin:1", "  "))
                .isInstanceOf(PointInvariantViolationException.class)
                .hasMessageContaining("사유");
    }
}
