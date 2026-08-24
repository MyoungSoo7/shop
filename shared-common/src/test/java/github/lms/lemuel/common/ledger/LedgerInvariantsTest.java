package github.lms.lemuel.common.ledger;

import github.lms.lemuel.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerInvariantsTest {

    private static IllegalStateException boom() {
        return new IllegalStateException("invariant");
    }

    @Test
    @DisplayName("requirePositiveAmount — 양수는 Money(scale 2)로 정규화해 반환")
    void positiveAmountNormalized() {
        Money money = LedgerInvariants.requirePositiveAmount(new BigDecimal("10"), LedgerInvariantsTest::boom);
        assertThat(money.toBigDecimal()).isEqualByComparingTo("10");
        assertThat(money.isPositive()).isTrue();
    }

    @Test
    @DisplayName("requirePositiveAmount — null·0·음수는 주입 예외로 거부")
    void positiveAmountRejectsNonPositive() {
        assertThatThrownBy(() -> LedgerInvariants.requirePositiveAmount(null, LedgerInvariantsTest::boom))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LedgerInvariants.requirePositiveAmount(BigDecimal.ZERO, LedgerInvariantsTest::boom))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LedgerInvariants.requirePositiveAmount(new BigDecimal("-1"), LedgerInvariantsTest::boom))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("requireDistinctAccounts — 다른 계정은 통과, 같은 계정은 주입 예외")
    void distinctAccounts() {
        assertThatCode(() -> LedgerInvariants.requireDistinctAccounts("CASH", "PAYABLE", LedgerInvariantsTest::boom))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> LedgerInvariants.requireDistinctAccounts("CASH", "CASH", LedgerInvariantsTest::boom))
                .isInstanceOf(IllegalStateException.class);
    }
}
