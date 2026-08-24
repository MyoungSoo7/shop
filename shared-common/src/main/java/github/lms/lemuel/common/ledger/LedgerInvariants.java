package github.lms.lemuel.common.ledger;

import github.lms.lemuel.common.money.Money;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * 복식부기 전표(journal entry)의 <b>구성적 균형 불변식</b> 단일 출처.
 *
 * <p>account GL({@code AccountEntry})·loan({@code LoanLedgerEntry})·settlement 원장({@code LedgerEntry})이
 * 각자 독립 구현하던 두 규칙 — ① 한 전표의 차변·대변 계정은 서로 달라야 하고(반쪽 전표 금지),
 * ② 금액은 양수여야 한다 — 를 여기 한 곳에 모은다. 계정 enum 과 도메인 예외 타입은 서비스마다 다르므로,
 * 각 도메인이 자신의 예외를 {@link Supplier} 로 주입해 계층·메시지 규약을 그대로 유지한다.
 *
 * <p>순수 함수(상태 없음, 프레임워크 의존 0) — 도메인 계층에서 직접 호출한다.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    /**
     * 금액을 공용 {@link Money} VO(scale 2 HALF_UP)로 정규화하고 <b>양수</b>를 강제한다.
     * {@code amount} 가 null 이거나 0 이하이면 {@code onNonPositive} 가 공급한 예외를 던진다.
     *
     * @return 정규화된 Money (호출부가 {@code toBigDecimal()} 로 영속 경계에 환원해 쓸 수 있다)
     */
    public static Money requirePositiveAmount(BigDecimal amount, Supplier<? extends RuntimeException> onNonPositive) {
        if (amount == null) {
            throw onNonPositive.get();
        }
        Money money = Money.of(amount);
        if (!money.isPositive()) {
            throw onNonPositive.get();
        }
        return money;
    }

    /**
     * 한 전표의 차변·대변 계정이 같으면(반쪽 전표) 균형 분개가 성립하지 않으므로
     * {@code onEqual} 이 공급한 예외를 던진다.
     */
    public static <A> void requireDistinctAccounts(A debit, A credit, Supplier<? extends RuntimeException> onEqual) {
        if (debit == credit || (debit != null && debit.equals(credit))) {
            throw onEqual.get();
        }
    }
}
