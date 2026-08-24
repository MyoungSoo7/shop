package github.lms.lemuel.giftcard.application.service;

import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase;
import github.lms.lemuel.giftcard.application.port.out.GiftCardEntryPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.application.port.out.PublishGiftCardEventPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import github.lms.lemuel.giftcard.domain.GiftCardStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 기프트카드 소멸 배치 — 유효기간이 지난 카드를 닫고 잔액을 소멸시킨다.
 *
 * <p>고객 재산을 지우는 작업이라 {@code dryRun} 으로 먼저 규모를 확인할 수 있게 한다
 * (포인트 소멸과 같은 규약).
 *
 * <p>소진된 카드는 이미 닫혀 있어 소멸 대상이 아니다(조회가 활성·등록만 훑는다). 그래도 경합이나
 * 조회 변경으로 섞여 들어올 수 있어, 한 행 때문에 배치가 죽지 않도록 건너뛴다.
 */
@Service
@Transactional
public class ExpireGiftCardsService implements ExpireGiftCardsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpireGiftCardsService.class);

    /** 소멸시킬 수 있는 상태 — 잔액이 아직 살아 있을 수 있는 것들. */
    private static final java.util.Set<GiftCardStatus> EXPIRABLE =
            java.util.EnumSet.of(GiftCardStatus.ACTIVE, GiftCardStatus.REGISTERED);

    /** 소멸 엔트리의 참조 종류 — 카드 하나가 근거이므로 카드 식별자를 참조 id 로 쓴다. */
    private static final String REFERENCE_TYPE = "CARD_EXPIRY";

    private final GiftCardPort giftCardPort;
    private final GiftCardEntryPort entryPort;
    private final PublishGiftCardEventPort eventPort;

    public ExpireGiftCardsService(GiftCardPort giftCardPort, GiftCardEntryPort entryPort,
                                  PublishGiftCardEventPort eventPort) {
        this.giftCardPort = giftCardPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public ExpireGiftCardsResult expire(ExpireGiftCardsCommand command) {
        List<GiftCard> expired = giftCardPort.loadExpired(command.at(), command.batchSize());
        if (expired.isEmpty()) {
            return new ExpireGiftCardsResult(0, BigDecimal.ZERO, command.dryRun());
        }

        if (command.dryRun()) {
            BigDecimal preview = expired.stream()
                    .map(GiftCard::getRemainingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            log.info("기프트카드 소멸 dry-run: cards={}, 소멸예정={}", expired.size(), preview);
            return new ExpireGiftCardsResult(expired.size(), preview, true);
        }

        BigDecimal total = BigDecimal.ZERO;
        List<GiftCard> closed = new ArrayList<>(expired.size());
        for (GiftCard card : expired) {
            if (!EXPIRABLE.contains(card.getStatus())) {
                // 조회 조건이 바뀌었거나 경합으로 이미 닫힌 카드가 섞여 들어온 경우다.
                // 한 행 때문에 야간 배치 전체가 죽지 않도록 건너뛰되, 조용히 넘기지는 않는다.
                log.warn("소멸 대상이 아닌 카드가 배치에 섞였다 — 건너뛴다: cardId={}, status={}",
                        card.getId(), card.getStatus());
                continue;
            }
            BigDecimal forfeited = card.expire(command.at());
            closed.add(card);
            total = total.add(forfeited);
            int sequence = entryPort.nextSequence(card.getId(), GiftCardEntryType.EXPIRE,
                    REFERENCE_TYPE, String.valueOf(card.getId()));
            entryPort.append(GiftCardEntry.expire(card.getId(), forfeited, REFERENCE_TYPE,
                    String.valueOf(card.getId()), sequence, command.actor()));
            eventPort.giftCardExpired(card, forfeited);
        }
        giftCardPort.saveAll(closed);

        log.info("기프트카드 소멸 완료: cards={}, 소멸액={}", closed.size(), total);
        return new ExpireGiftCardsResult(closed.size(), total, false);
    }
}
