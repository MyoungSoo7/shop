package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

/**
 * 환불 수취 계좌 — <b>PG 로 되돌릴 수 없는 결제</b>의 돈이 돌아갈 곳.
 *
 * <p>카드·간편결제는 승인을 취소하면 그 카드로 돌아가므로 계좌가 필요 없다. 무통장 입금과
 * 가상계좌는 다르다({@code TenderType.awaitsDeposit()}). 들어온 길이 "고객이 우리 계좌로 보냄"
 * 이라 되돌릴 길이 없고, 사람이 고객 계좌로 송금해야 한다. 그 계좌를 신청 시점에 받지 않으면
 * 환불 승인까지 끝난 주문의 돈이 갈 곳을 몰라 멈춘다.
 *
 * <p>세 칸은 함께 있거나 함께 없다. 예금주 없이 계좌번호만 있으면 송금 창구에서 막히고,
 * 은행 코드가 없으면 어느 은행인지 추측해야 한다 — 반쪽짜리 계좌는 없는 것과 같다
 * (DB 의 {@code ck_order_return_requests_refund_account} 가 같은 규칙을 강제한다).
 *
 * <p>계좌번호는 하이픈·공백을 지운 숫자만 보관한다. 같은 계좌가 표기만 달라 다른 값으로
 * 남으면 중복 송금을 눈으로 걸러낼 수 없다.
 */
public record RefundAccount(String bankCode, String accountNumber, String holderName) {

    private static final int MAX_BANK_CODE = 20;
    private static final int MAX_ACCOUNT_NUMBER = 60;
    private static final int MAX_HOLDER_NAME = 60;

    public RefundAccount {
        bankCode = require(bankCode, "은행 코드", MAX_BANK_CODE);
        accountNumber = require(normalizeAccount(accountNumber), "계좌번호", MAX_ACCOUNT_NUMBER);
        holderName = require(holderName, "예금주", MAX_HOLDER_NAME);
        if (!accountNumber.chars().allMatch(Character::isDigit)) {
            throw new OrderInvariantViolationException("계좌번호는 숫자만 입력합니다");
        }
    }

    /** 세 칸이 하나라도 비어 있으면 {@code null} — 계좌를 내지 않은 신청과 반쪽 계좌를 같게 다룬다. */
    public static RefundAccount ofNullable(String bankCode, String accountNumber, String holderName) {
        if (isBlank(bankCode) && isBlank(accountNumber) && isBlank(holderName)) {
            return null;
        }
        return new RefundAccount(bankCode, accountNumber, holderName);
    }

    /** 로그·화면에 쓰는 가림 표기 — 뒤 4 자리만 남긴다. */
    public String maskedAccountNumber() {
        if (accountNumber.length() <= 4) {
            return "*".repeat(accountNumber.length());
        }
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }

    private static String normalizeAccount(String raw) {
        return raw == null ? null : raw.replaceAll("[\\s-]", "");
    }

    private static String require(String value, String label, int maxLength) {
        if (isBlank(value)) {
            throw new OrderInvariantViolationException(label + " 필수");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new OrderInvariantViolationException(label + " 는 " + maxLength + "자를 넘을 수 없습니다");
        }
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
