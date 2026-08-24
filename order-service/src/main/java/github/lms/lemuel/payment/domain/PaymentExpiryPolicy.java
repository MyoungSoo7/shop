package github.lms.lemuel.payment.domain;

import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 미입금 결제 만료 정책 (순수 도메인 규칙 — 프레임워크·시계 의존 0).
 *
 * <p>가상계좌·무통장 입금은 PG 승인이 즉시 오지 않고 구매자의 입금을 기다린다. 입금이 끝내 오지 않으면
 * 결제는 {@link PaymentStatus#READY} 로, 주문은 CREATED 로 영구 잔류하며 주문 생성 시 차감된 재고를 붙잡는다.
 * 이 정책이 "어떤 수단이, 언제부터 만료인지"를 정하고, 전이 자체는 {@link PaymentDomain#expire()} 가 강제한다.
 *
 * <p><b>모르면 만료시키지 않는다</b> — 수단 문자열이 미상이거나 생성시각이 없으면 만료 대상에서 뺀다.
 * 오탐(정상 결제 취소)이 미탐(만료 누락)보다 훨씬 비싸기 때문이다. 누락분은 다음 주기가 다시 집는다.
 *
 * <p>기한 산정은 현재 {@code createdAt + ttl} 이다. 추후 PG 가 발급 응답으로 입금 기한을 내려주면
 * 그 값을 결제에 보존하고 여기에 주입하는 형태로 확장한다(현 시점엔 발급 연동이 없어 도입하지 않는다).
 */
public final class PaymentExpiryPolicy {

    private PaymentExpiryPolicy() {
    }

    /**
     * 입금 대기형(만료 대상) 수단인지. 미상값·null 은 false.
     *
     * <p>목록을 여기에 다시 두지 않고 {@link TenderType#awaitsDeposit()} 에 묻는다 — 같은 사실이
     * 두 곳에 적혀 있으면 수단을 추가할 때 한쪽만 고쳐지고 그 사실을 아무도 모른다.
     */
    public static boolean isDepositMethod(String paymentMethod) {
        return parse(paymentMethod).map(TenderType::awaitsDeposit).orElse(false);
    }

    /** 입금 기한 = 생성시각 + TTL. */
    public static LocalDateTime deadline(LocalDateTime createdAt, Duration ttl) {
        if (createdAt == null) {
            throw new PaymentInvariantViolationException("결제 생성시각 없이 입금 기한을 계산할 수 없습니다");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new PaymentInvariantViolationException(
                    "입금 대기 TTL 은 양수여야 합니다: " + ttl + " (0·음수는 대기 중 결제 전량 즉시 만료)");
        }
        return createdAt.plus(ttl);
    }

    /**
     * 만료 여부. 입금 대기형 수단이고 기한을 <b>지났을</b> 때만 true (기한 정각은 아직 만료가 아니다).
     * 판정 불가(수단 미상·생성시각 없음)면 false.
     */
    public static boolean isExpired(String paymentMethod, LocalDateTime createdAt,
                                    Duration ttl, LocalDateTime now) {
        if (!isDepositMethod(paymentMethod) || createdAt == null || now == null) {
            return false;
        }
        return now.isAfter(deadline(createdAt, ttl));
    }

    /**
     * 결제 기준 만료 판정 — <b>텐더 결제는 반드시 이쪽</b>을 쓴다.
     *
     * <p>{@link #isExpired(String, LocalDateTime, Duration, LocalDateTime)} 는 수단 문자열만 보므로
     * {@code "SPLIT:CARD"} 라벨 뒤에 숨은 가상계좌 텐더를 놓친다 — 그 결제는 만료 후보에 들어가지
     * 못하고 재고를 붙잡은 채 잔류한다. 판정의 진실원은 텐더 목록이다
     * ({@link PaymentDomain#awaitsDeposit()}).
     */
    public static boolean isExpired(PaymentDomain payment, Duration ttl, LocalDateTime now) {
        if (payment == null || now == null || payment.getCreatedAt() == null
                || !payment.awaitsDeposit()) {
            return false;
        }
        return now.isAfter(deadline(payment.getCreatedAt(), ttl));
    }

    private static java.util.Optional<TenderType> parse(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(TenderType.valueOf(paymentMethod.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty(); // 미상 수단 — 만료 대상 아님
        }
    }
}
