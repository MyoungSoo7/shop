package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.GiftClaim;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 선물 수령 레코드 조회. */
public interface LoadGiftClaimPort {

    /**
     * 링크 토큰의 <b>해시</b>로 찾는다 — 평문으로 찾는 메서드는 두지 않는다.
     *
     * <p>평문을 인자로 받는 순간 그 값이 쿼리 로그·APM 파라미터·슬로우 쿼리 기록에 남는다.
     * 이 토큰은 로그인 없이 남의 주문 화면을 여는 열쇠라 그 흔적 하나가 곧 유출이다.
     */
    Optional<GiftClaim> findByTokenHash(String tokenHash);

    Optional<GiftClaim> findByOrderId(Long orderId);

    /** 기한이 지났는데 아직 열려 있는 것들 — 소멸 배치용, 오래된 것부터. */
    List<GiftClaim> findExpirable(LocalDateTime now, int limit);
}
