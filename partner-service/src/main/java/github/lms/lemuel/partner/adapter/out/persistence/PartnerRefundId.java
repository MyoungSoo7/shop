package github.lms.lemuel.partner.adapter.out.persistence;

import java.io.Serializable;
import java.util.Objects;

/** {@code partner_refunds} 복합 PK — (결제, 환불키). 환불키는 refundId 또는 event_id 다. */
class PartnerRefundId implements Serializable {

    private Long paymentId;
    private String refundKey;

    protected PartnerRefundId() {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PartnerRefundId other)) {
            return false;
        }
        return Objects.equals(paymentId, other.paymentId) && Objects.equals(refundKey, other.refundKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(paymentId, refundKey);
    }
}
