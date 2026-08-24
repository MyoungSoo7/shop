package github.lms.lemuel.payment.domain;

import github.lms.lemuel.payment.domain.exception.CashReceiptNotAllowedException;

import java.util.Objects;

/**
 * 현금영수증 식별번호 — 종류 + 정규화된 값 (불변 VO).
 *
 * <p><b>주민등록번호는 받지 않는다.</b> 국세청 규격상 소득공제 식별번호로 쓸 수는 있지만, 저장하는
 * 순간 고유식별정보가 되어 별도 암호화·파기 의무가 붙는다. 휴대폰번호로도 동일한 소득공제가
 * 가능하므로 받지 않는 쪽이 옳다 — 안 받은 데이터는 새지 않는다.
 *
 * <p>값은 <b>항상 숫자만 남긴 형태로 정규화</b>해 보관한다. "010-1234-5678" 과 "01012345678" 이
 * 다른 값으로 저장되면 같은 사람의 발급 이력이 갈라지고 중복 발급 판정이 무너진다.
 */
public final class CashReceiptIdentifier {

    /** 사업자등록번호 검증 가중치 — 국세청 체크섬 규격(마지막 자리는 검증 대상). */
    private static final int[] BUSINESS_NUMBER_WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

    public enum Type {
        /** 휴대폰번호(소득공제) — 010/011/016~019. */
        MOBILE,
        /** 현금영수증 전용카드(소득공제) — 카드 뒷면 13~19 자리. */
        CASH_RECEIPT_CARD,
        /** 사업자등록번호(지출증빙) — 10 자리 + 체크섬. */
        BUSINESS_NUMBER
    }

    private final Type type;
    private final String value;

    private CashReceiptIdentifier(Type type, String value) {
        this.type = type;
        this.value = value;
    }

    /**
     * 종류별 형식 검증 후 정규화 값으로 생성한다. 형식 위반은 여기서 끝난다 — 국세청까지 갔다가
     * 반려로 돌아오면 고객은 "발급 신청했는데 며칠 뒤 실패"를 겪는다.
     */
    public static CashReceiptIdentifier of(Type type, String raw) {
        if (type == null) {
            throw new CashReceiptNotAllowedException("현금영수증 식별번호 종류가 없습니다");
        }
        String digits = digitsOnly(raw);
        if (digits.isEmpty()) {
            throw new CashReceiptNotAllowedException("현금영수증 식별번호가 비어 있습니다");
        }
        switch (type) {
            case MOBILE -> requireMobile(digits);
            case CASH_RECEIPT_CARD -> requireCashReceiptCard(digits);
            case BUSINESS_NUMBER -> requireBusinessNumber(digits);
        }
        return new CashReceiptIdentifier(type, digits);
    }

    /** 저장된 값 복원 전용 — 이미 검증을 통과한 값이므로 다시 막지 않는다. */
    public static CashReceiptIdentifier restore(Type type, String storedValue) {
        return new CashReceiptIdentifier(type, storedValue);
    }

    private static void requireMobile(String digits) {
        if (!digits.matches("^01[016789]\\d{7,8}$")) {
            throw new CashReceiptNotAllowedException("휴대폰번호 형식이 올바르지 않습니다");
        }
    }

    private static void requireCashReceiptCard(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            throw new CashReceiptNotAllowedException("현금영수증카드 번호는 13~19 자리여야 합니다");
        }
    }

    /**
     * 사업자등록번호 10 자리 + 국세청 체크섬.
     *
     * <p>길이만 보면 오타가 그대로 통과해 <b>남의 사업자번호로 지출증빙이 발급</b>된다(발급 취소는
     * 사후 정정 신고 대상이라 되돌리는 비용이 크다). 체크섬은 한 자리 오타·자리 바꿈을 그 자리에서 잡는다.
     */
    private static void requireBusinessNumber(String digits) {
        if (digits.length() != 10) {
            throw new CashReceiptNotAllowedException("사업자등록번호는 10 자리여야 합니다");
        }
        int sum = 0;
        for (int i = 0; i < BUSINESS_NUMBER_WEIGHTS.length; i++) {
            sum += (digits.charAt(i) - '0') * BUSINESS_NUMBER_WEIGHTS[i];
        }
        // 8번째 자리(index 8)의 가중곱은 10 의 자리까지 다시 더한다 — 규격에 명시된 보정.
        sum += ((digits.charAt(8) - '0') * 5) / 10;
        int check = (10 - (sum % 10)) % 10;
        if (check != digits.charAt(9) - '0') {
            throw new CashReceiptNotAllowedException("사업자등록번호 체크섬이 맞지 않습니다");
        }
    }

    private static String digitsOnly(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    /**
     * 화면·로그 노출용 마스킹 — 뒤 4 자리만 남긴다.
     *
     * <p>원문을 그대로 응답에 실으면 "내 주문 조회"만 뚫려도 휴대폰번호·사업자번호가 통째로 샌다.
     * 본인 확인에는 뒤 4 자리로 충분하다.
     */
    public String masked() {
        int keep = 4;
        if (value.length() <= keep) {
            return "*".repeat(value.length());
        }
        return "*".repeat(value.length() - keep) + value.substring(value.length() - keep);
    }

    public Type getType() {
        return type;
    }

    /** 원문(정규화된 숫자열). 외부 전송·저장 전용 — 응답에는 {@link #masked()} 를 쓴다. */
    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CashReceiptIdentifier other)) return false;
        return type == other.type && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    /** 로그에 실려도 안전하도록 마스킹된 형태만 내보낸다. */
    @Override
    public String toString() {
        return type + ":" + masked();
    }
}
