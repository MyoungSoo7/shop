package github.lms.lemuel.giftcard.application.port.out;

import github.lms.lemuel.giftcard.domain.GiftCard;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 기프트카드 적재·저장 포트.
 *
 * <p>기프트카드에는 잔액 요약 계정이 없다 — 잠글 대상이 <b>카드 행</b>이다. 여러 장을 걸쳐 쓸 때는
 * {@link #loadForUpdate} 가 카드 id 오름차순으로 잠가 교착을 피한다. 잠금 순서를 호출자에게
 * 맡기면 두 결제가 서로 다른 순서로 같은 두 장을 잡는 순간 데드락이 난다.
 */
public interface GiftCardPort {

    /** 등록 경로 — 코드 해시로 카드를 찾아 잠근다(등록은 상태를 바꾸므로 락이 필요하다). */
    Optional<GiftCard> loadByCodeHashForUpdate(String codeHash);

    /** 결제 재원 — 그 사용자가 쓸 수 있는(REGISTERED, 잔액 > 0) 카드. 정렬은 도메인이 책임진다. */
    List<GiftCard> loadSpendable(Long userId);

    /** 잔액 조회용(락 없음). */
    List<GiftCard> loadSpendableReadOnly(Long userId);

    /** 환불 복원 — 원 사용 엔트리가 가리키는 카드들을 id 순으로 잠근다. */
    List<GiftCard> loadForUpdate(Collection<Long> giftCardIds);

    /** 소멸 배치 — {@code expiresAt < at} 이면서 아직 살아 있는 카드를 최대 {@code limit} 장. */
    List<GiftCard> loadExpired(OffsetDateTime at, int limit);

    /** 발행 시 코드 해시 충돌 확인 — 천문학적으로 드물지만, 충돌하면 남의 카드를 덮어쓴다. */
    boolean existsByCodeHash(String codeHash);

    GiftCard save(GiftCard card);

    List<GiftCard> saveAll(List<GiftCard> cards);
}
