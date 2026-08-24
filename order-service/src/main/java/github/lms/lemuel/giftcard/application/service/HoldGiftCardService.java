package github.lms.lemuel.giftcard.application.service;

import github.lms.lemuel.giftcard.application.port.in.HoldGiftCardUseCase;
import github.lms.lemuel.giftcard.application.port.out.GiftCardEntryPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardHoldPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.application.port.out.PublishGiftCardEventPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardCharge;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import github.lms.lemuel.giftcard.domain.GiftCardHold;
import github.lms.lemuel.giftcard.domain.GiftCardHoldStatus;
import github.lms.lemuel.giftcard.domain.GiftCardSelector;
import github.lms.lemuel.giftcard.domain.exception.GiftCardInvariantViolationException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기프트카드 선점 — 입금 대기 결제가 잠그고, 확정하고, 푸는 세 경로.
 *
 * <p>포인트 선점과 다른 점 둘.
 *
 * <ul>
 *   <li><b>한 근거가 카드 여러 장에 걸친다.</b> 상품권은 권면가 단위로 발행되어 한 장으로 못
 *       채우는 경우가 있다. 그래서 선점 레코드가 (근거 × 카드) 단위이고, 확정·해제는 그 여러 장을
 *       한 번에 다룬다.
 *   <li><b>잠긴 금액을 저장하지 않는다.</b> 선점 시점에 카드 잔액을 <b>전혀 건드리지 않고</b>,
 *       가용액을 물을 때마다 {@code remaining − Σ(활성 선점)} 으로 계산한다. 그 결과 해제는
 *       되돌릴 잔액이 없어 상태만 바뀐다 — 포인트가 {@code locked → available} 로 옮기던 것과 다르다.
 * </ul>
 *
 * <p><b>잠금 순서</b>: 확정·해제는 근거로 <b>카드 id 만 먼저</b> 읽고, 카드를 잠근 <b>뒤에</b>
 * 선점을 처음 적재한다. 잠금 전에 선점을 읽어 두면 (가) 읽은 시점과 잠금 시점 사이에 다른
 * 트랜잭션이 해소할 수 있고 (나) 그래서 재조회해도 영속성 컨텍스트의 낡은 인스턴스가 돌아온다.
 * 포인트 선점에서 동시성 IT 가 실제로 잡은 결함이라 같은 순서를 처음부터 지킨다.
 */
@Service
@Transactional
public class HoldGiftCardService implements HoldGiftCardUseCase {

    private static final Logger log = LoggerFactory.getLogger(HoldGiftCardService.class);

    private final GiftCardPort giftCardPort;
    private final GiftCardHoldPort holdPort;
    private final GiftCardEntryPort entryPort;
    private final PublishGiftCardEventPort eventPort;

    public HoldGiftCardService(GiftCardPort giftCardPort, GiftCardHoldPort holdPort,
                               GiftCardEntryPort entryPort, PublishGiftCardEventPort eventPort) {
        this.giftCardPort = giftCardPort;
        this.holdPort = holdPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        // 결제 재시도가 선점을 두 벌 만들면 같은 잔액을 두 번 잠근다. DB UNIQUE 가 최후 방어선이지만,
        // 정상 경로에서 먼저 걸러야 재시도가 예외로 시끄러워지지 않는다.
        List<Long> existing = holdPort.findCardIdsByReference(
                command.referenceType(), command.referenceId());
        if (!existing.isEmpty()) {
            log.info("기프트카드 선점 멱등 단축 반환: userId={}, ref={}:{}, cards={}",
                    command.userId(), command.referenceType(), command.referenceId(), existing.size());
            return new HoldResult(existing.size(), command.amount());
        }

        List<GiftCard> cards = giftCardPort.loadSpendable(command.userId());
        Map<Long, BigDecimal> held = holdPort.activeAmountsByCardIds(cardIds(cards));

        // 계획만 세운다 — 카드 잔액은 확정 시점에야 움직인다. 부족하면 여기서 끝나고
        // 아무 카드도 건드리지 않은 상태로 거절된다.
        List<GiftCardCharge> plan = GiftCardSelector.plan(cards, command.amount(), held);

        OffsetDateTime now = OffsetDateTime.now();
        List<GiftCardHold> holds = new ArrayList<>(plan.size());
        for (GiftCardCharge charge : plan) {
            holds.add(GiftCardHold.place(charge.giftCardId(), charge.amount(),
                    command.referenceType(), command.referenceId(), now));
        }
        holdPort.saveHolds(holds);

        log.info("기프트카드 선점: userId={}, amount={}, cards={}",
                command.userId(), command.amount(), holds.size());
        return new HoldResult(holds.size(), command.amount());
    }

    @Override
    public void capture(String referenceType, String referenceId, String actor) {
        List<Long> cardIds = holdPort.findCardIdsByReference(referenceType, referenceId);
        if (cardIds.isEmpty()) {
            throw new GiftCardInvariantViolationException(
                    "확정할 기프트카드 선점이 없습니다: ref=" + referenceType + ":" + referenceId
                            + " — 선점 없이 확정하면 받지 않은 상품권을 받은 셈이 된다");
        }

        // 카드를 먼저 잠그고(포트가 id 오름차순으로 잠가 교착을 피한다) 그 안에서 선점을 처음 적재한다.
        Map<Long, GiftCard> byId = byId(giftCardPort.loadForUpdate(cardIds));
        List<GiftCardHold> holds = holdPort.findByReference(referenceType, referenceId);

        List<GiftCard> touched = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (GiftCardHold hold : holds) {
            if (hold.getStatus() == GiftCardHoldStatus.CAPTURED) {
                continue;   // 멱등 — 웹훅 재전송
            }
            if (!hold.isActive()) {
                // 만료·해제가 먼저 이겼다. 확정하면 이미 돌아간 잔액을 한 번 더 쓴다.
                throw new InvalidGiftCardStateException(
                        "이미 해소된 선점은 확정할 수 없습니다: " + hold.getStatus(),
                        hold.getStatus().name(), "capture");
            }
            GiftCard card = byId.get(hold.getGiftCardId());
            if (card == null) {
                throw new GiftCardInvariantViolationException(
                        "선점이 가리키는 카드가 없습니다: giftCardId=" + hold.getGiftCardId());
            }
            card.use(hold.getAmount());
            touched.add(card);
            hold.capture(now);
            holdPort.save(hold);
        }

        if (touched.isEmpty()) {
            log.info("기프트카드 선점 확정 멱등 단축 반환: ref={}:{}", referenceType, referenceId);
            return;
        }

        giftCardPort.saveAll(touched);
        for (GiftCardHold hold : holds) {
            if (hold.getStatus() != GiftCardHoldStatus.CAPTURED || !now.equals(hold.getResolvedAt())) {
                continue;   // 이번 호출이 확정한 건만 원장에 적는다
            }
            GiftCard card = byId.get(hold.getGiftCardId());
            int sequence = entryPort.nextSequence(card.getId(), GiftCardEntryType.USE,
                    referenceType, referenceId);
            GiftCardEntry entry = entryPort.append(GiftCardEntry.use(card.getId(), hold.getAmount(),
                    referenceType, referenceId, sequence, actor));
            eventPort.giftCardUsed(card, entry);
        }

        log.info("기프트카드 선점 확정: ref={}:{}, cards={}", referenceType, referenceId, touched.size());
    }

    @Override
    public void release(String referenceType, String referenceId, boolean expired) {
        List<Long> cardIds = holdPort.findCardIdsByReference(referenceType, referenceId);
        if (cardIds.isEmpty()) {
            // 상품권을 쓰지 않은 결제이거나 애초에 선점하지 않은 건. 막으면 만료 배치가 함께 멈춘다.
            log.warn("해제할 기프트카드 선점이 없습니다 — 건너뜁니다: ref={}:{}", referenceType, referenceId);
            return;
        }

        giftCardPort.loadForUpdate(cardIds);   // 확정과 같은 순서로 잠가 경합을 줄 세운다
        List<GiftCardHold> holds = holdPort.findByReference(referenceType, referenceId);

        OffsetDateTime now = OffsetDateTime.now();
        for (GiftCardHold hold : holds) {
            if (hold.getStatus() == GiftCardHoldStatus.CAPTURED) {
                // 입금이 먼저 이겼다. 풀면 이미 쓴 잔액이 되살아나 없는 재산이 생긴다.
                throw new InvalidGiftCardStateException(
                        "이미 확정된 선점은 해제할 수 없습니다", hold.getStatus().name(), "release");
            }
            if (!hold.isActive()) {
                continue;   // 멱등 — 배치 재실행
            }
            // 카드 잔액은 애초에 건드리지 않았다. 되돌릴 잔액이 없으므로 상태만 바뀐다.
            if (expired) {
                hold.expire(now);
            } else {
                hold.release(now);
            }
            holdPort.save(hold);
        }

        log.info("기프트카드 선점 해제: ref={}:{}, cards={}, 만료={}",
                referenceType, referenceId, holds.size(), expired);
    }

    private static List<Long> cardIds(List<GiftCard> cards) {
        return cards.stream().map(GiftCard::getId).toList();
    }

    private static Map<Long, GiftCard> byId(List<GiftCard> cards) {
        Map<Long, GiftCard> map = new HashMap<>();
        for (GiftCard card : cards) {
            map.put(card.getId(), card);
        }
        return map;
    }
}
