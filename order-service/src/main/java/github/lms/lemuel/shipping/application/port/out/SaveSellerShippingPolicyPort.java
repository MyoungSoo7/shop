package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.SellerShippingPolicy;

/** 셀러 배송비 정책 저장(신규 등록/변경 겸용 upsert). */
public interface SaveSellerShippingPolicyPort {

    SellerShippingPolicy save(SellerShippingPolicy policy);
}
