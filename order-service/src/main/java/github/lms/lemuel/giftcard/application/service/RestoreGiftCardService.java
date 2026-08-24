package github.lms.lemuel.giftcard.application.service;

import github.lms.lemuel.giftcard.application.port.in.RestoreGiftCardUseCase;
import github.lms.lemuel.giftcard.application.port.out.GiftCardEntryPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.application.port.out.PublishGiftCardEventPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import github.lms.lemuel.giftcard.domain.GiftCardStatus;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기프트카드 환불 복원 — 사용의 대칭.
 *
 * <p>되돌리는 순서는 <b>역순(LIFO)</b>이다. 사용은 만료 임박분부터 쓰므로, 되돌릴 때 마지막에 쓴
 * 장부터 돌려주면 고객은 유효기간이 더 긴 잔액을 돌려받는다(포인트 원장과 같은 판단).
 *
 * <p>복원 대상은 원 사용 엔트리에서 도출한다 — 환불은 낸 사람에게 돌아가야 하므로 호출자가
 * 넘긴 식별자를 믿지 않는다. 이미 <b>소멸·정지</b>된 카드에는 되돌리지 않는다: 소멸분을 되살리면
 * 유효기간이 무의미해지고, 정지분을 되살리면 분실 신고가 무력해진다.
 */
@Service
@Transactional
public class RestoreGiftCardService implements RestoreGiftCardUseCase {

    private static final Logger log = LoggerFactory.getLogger(RestoreGiftCardService.class);

    private final GiftCardPort giftCardPort;
    private final GiftCardEntryPort entryPort;
    private final PublishGiftCardEventPort eventPort;

    public RestoreGiftCardService(GiftCardPort giftCardPort, GiftCardEntryPort entryPort,
                                  PublishGiftCardEventPort eventPort) {
        this.giftCardPort = giftCardPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public RestoreGiftCardResult restore(RestoreGiftCardCommand command) {
        Map<Long, BigDecimal> usedByCard = usedAmountsByCard(command);
        List<Long> reverseOrder = new ArrayList<>(usedByCard.keySet());
        Collections.reverse(reverseOrder);

        Map<Long, GiftCard> cards = new LinkedHashMap<>();
        for (GiftCard card : giftCardPort.loadForUpdate(usedByCard.keySet())) {
            cards.put(card.getId(), card);
        }

        List<GiftCard> touched = new ArrayList<>();
        BigDecimal remaining = command.amount();
        for (Long cardId : reverseOrder) {
            if (remaining.signum() == 0) {
                break;
            }
            GiftCard card = cards.get(cardId);
            if (card == null || !isRestorable(card)) {
                continue;
            }
            int sequence = entryPort.nextSequence(cardId, GiftCardEntryType.RESTORE,
                    command.referenceType(), command.referenceId());
            if (entryPort.existsByReference(cardId, GiftCardEntryType.RESTORE,
                    command.referenceType(), command.referenceId())) {
                // 같은 환불을 두 번 반영하지 않는다.
                continue;
            }

            BigDecimal give = usedByCard.get(cardId).min(remaining);
            card.restore(give);
            touched.add(card);
            GiftCardEntry entry = entryPort.append(GiftCardEntry.restore(cardId, give,
                    command.referenceType(), command.referenceId(), sequence, command.actor()));
            eventPort.giftCardRestored(card, entry);
            remaining = remaining.subtract(give);
        }

        if (remaining.signum() > 0) {
            // 소멸·정지된 카드만 남았거나 요청이 원 사용액을 넘었다. 잔액을 만들어 내지 않는다.
            throw new InvalidGiftCardStateException(
                    "복원할 수 있는 카드가 부족합니다: 남은 요청 " + remaining, "NONE", "restore");
        }
        giftCardPort.saveAll(touched);

        log.info("기프트카드 복원: amount={}, cards={}, ref={}:{}",
                command.amount(), touched.size(), command.referenceType(), command.referenceId());
        return new RestoreGiftCardResult(command.amount(), touched.size());
    }

    /** 원 사용 엔트리에서 "어느 카드를 얼마나 썼는지"를 사용 순서대로 모은다. */
    private Map<Long, BigDecimal> usedAmountsByCard(RestoreGiftCardCommand command) {
        List<GiftCardEntry> used = entryPort.loadByReference(GiftCardEntryType.USE,
                command.sourceReferenceType(), command.sourceReferenceId());
        if (used.isEmpty()) {
            throw new InvalidGiftCardStateException(
                    "복원 근거가 되는 사용 이력이 없습니다: " + command.sourceReferenceType()
                            + ":" + command.sourceReferenceId(), "NONE", "restore");
        }
        Map<Long, BigDecimal> usedByCard = new LinkedHashMap<>();
        for (GiftCardEntry entry : used) {
            usedByCard.merge(entry.getGiftCardId(), entry.getAmount(), BigDecimal::add);
        }
        return usedByCard;
    }

    private static boolean isRestorable(GiftCard card) {
        return card.getStatus() != GiftCardStatus.EXPIRED
                && card.getStatus() != GiftCardStatus.SUSPENDED;
    }
}
