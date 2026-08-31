package github.lms.lemuel.seller.adapter.out.persistence;

import java.io.Serializable;
import java.util.Objects;

/** {@code seller_refunds} 복합 PK — (결제, 환불키). 환불키는 refundId 또는 event_id 다. */
class SellerRefundId implements Serializable {

    private Long paymentId;
    private String refundKey;

    protected SellerRefundId() {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SellerRefundId other)) {
            return false;
        }
        return Objects.equals(paymentId, other.paymentId) && Objects.equals(refundKey, other.refundKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(paymentId, refundKey);
    }
}
