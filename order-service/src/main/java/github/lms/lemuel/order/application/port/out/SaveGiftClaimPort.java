package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.GiftClaim;

/** 선물 수령 레코드 저장. */
public interface SaveGiftClaimPort {

    /**
     * 저장하고 식별자가 채워진 결과를 돌려준다.
     *
     * <p>인증 실패도 저장 대상이다 — 시도 횟수는 실패했을 때만 올라가는데, 그 트랜잭션이
     * 롤백되면 카운터가 되돌아가 {@link GiftClaim#MAX_VERIFY_ATTEMPTS} 가 무의미해진다.
     */
    GiftClaim save(GiftClaim claim);
}
