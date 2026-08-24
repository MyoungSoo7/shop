package github.lms.lemuel.giftcard.application.port.out;

import github.lms.lemuel.giftcard.domain.GiftCardHold;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 기프트카드 선점 적재·저장 포트.
 *
 * <p>조회를 <b>참조({@code referenceType}/{@code referenceId})로만</b> 한다 — 확정·해제를 부르는
 * 쪽(입금 확인·만료 배치)은 tender 만 쥐고 있고 어느 카드인지는 모른다. 카드는 선점 레코드가
 * 알려 준다. 호출자가 넘긴 카드를 믿으면 남의 카드 잠금을 푸는 통로가 된다.
 */
public interface GiftCardHoldPort {

    /** 이름이 {@code saveAll} 이 아닌 이유: 같은 어댑터가 구현하는 {@code GiftCardPort.saveAll(List<GiftCard>)}
     * 와 제네릭 소거 후 시그니처가 같아진다. */
    List<GiftCardHold> saveHolds(List<GiftCardHold> holds);

    GiftCardHold save(GiftCardHold hold);

    /** 근거에 걸린 선점 전부. 한 근거가 카드 여러 장에 걸칠 수 있다. */
    List<GiftCardHold> findByReference(String referenceType, String referenceId);

    /**
     * 근거에 걸린 <b>카드 id 만</b> 읽는다 — 선점 자체를 적재하지 않고.
     *
     * <p>확정·해제는 카드 잠금을 먼저 얻어야 하는데, 잠금 전에 선점을 통째로 읽으면 잠금 이후
     * 재조회가 낡은 상태를 돌려준다(영속성 컨텍스트 캐시). 그래서 id 만 먼저 묻는다.
     */
    List<Long> findCardIdsByReference(String referenceType, String referenceId);

    /**
     * 카드별 활성 선점 합계 — 가용액({@code remaining − 잠긴 몫}) 계산의 재료.
     * 선점이 없는 카드는 결과에서 빠진다(호출자가 0 으로 읽는다).
     */
    Map<Long, BigDecimal> activeAmountsByCardIds(Collection<Long> cardIds);
}
