package github.lms.lemuel.payment.domain;

/**
 * 현금영수증 발급 용도 — 국세청 신고 구분이자, <b>어떤 식별번호를 받을 수 있는지</b>를 정하는 축.
 *
 * <p>용도와 식별번호는 따로 고를 수 있는 값이 아니다. 소득공제인데 사업자등록번호를 적거나,
 * 지출증빙인데 휴대폰번호를 적으면 국세청에서 그대로 반려된다. 그래서 "허용 식별번호"를
 * 용도 자신이 들고 있고({@link #allows}), 조합 검증이 한 곳에서만 일어난다.
 */
public enum CashReceiptPurpose {

    /** 소득공제용(개인) — 연말정산에 잡힌다. 휴대폰번호 또는 현금영수증카드. */
    INCOME_DEDUCTION("소득공제") {
        @Override
        public boolean allows(CashReceiptIdentifier.Type type) {
            return type == CashReceiptIdentifier.Type.MOBILE
                    || type == CashReceiptIdentifier.Type.CASH_RECEIPT_CARD;
        }
    },

    /** 지출증빙용(사업자) — 매입세액공제에 쓰인다. 사업자등록번호만. */
    EXPENSE_PROOF("지출증빙") {
        @Override
        public boolean allows(CashReceiptIdentifier.Type type) {
            return type == CashReceiptIdentifier.Type.BUSINESS_NUMBER;
        }
    };

    private final String label;

    CashReceiptPurpose(String label) {
        this.label = label;
    }

    /** 이 용도로 쓸 수 있는 식별번호 종류인지. */
    public abstract boolean allows(CashReceiptIdentifier.Type type);

    public String label() {
        return label;
    }
}
