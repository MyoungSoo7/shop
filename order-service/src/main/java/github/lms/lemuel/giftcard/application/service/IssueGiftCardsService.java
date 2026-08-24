package github.lms.lemuel.giftcard.application.service;

import github.lms.lemuel.giftcard.application.port.in.IssueGiftCardsUseCase;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardCode;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 기프트카드 발행 — 코드를 만들고 카드를 찍어 낸다.
 *
 * <p>발행에는 <b>회계 이벤트가 없다.</b> 아직 아무에게도 가지 않은 코드는 회사의 빚이 아니다.
 * 부채는 등록 시점에 생긴다({@code docs/plan/gift-card-ledger.md} §6).
 *
 * <p>평문 코드는 이 서비스의 반환값에만 존재하고 어디에도 저장되지 않는다. 로그에도 남기지 않는다 —
 * 로그가 곧 코드 유출 경로가 된다.
 */
@Service
@Transactional
public class IssueGiftCardsService implements IssueGiftCardsUseCase {

    private static final Logger log = LoggerFactory.getLogger(IssueGiftCardsService.class);

    /** 한 번에 찍을 수 있는 상한 — 실수로 10만 장을 발행하는 사고를 막는다. */
    private static final int MAX_QUANTITY = 1_000;

    private final GiftCardPort giftCardPort;

    public IssueGiftCardsService(GiftCardPort giftCardPort) {
        this.giftCardPort = giftCardPort;
    }

    @Override
    public List<IssuedGiftCard> issue(IssueGiftCardsCommand command) {
        if (command.quantity() <= 0 || command.quantity() > MAX_QUANTITY) {
            throw new InvalidGiftCardStateException(
                    "발행 장수는 1 이상 " + MAX_QUANTITY + " 이하여야 합니다: " + command.quantity(),
                    "NONE", "issue");
        }
        if (command.validityDays() <= 0) {
            throw new InvalidGiftCardStateException(
                    "유효기간 일수는 양수여야 합니다: " + command.validityDays(), "NONE", "issue");
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusDays(command.validityDays());

        List<IssuedGiftCard> issued = new ArrayList<>(command.quantity());
        for (int i = 0; i < command.quantity(); i++) {
            String rawCode = generateUnusedCode();
            GiftCard card = GiftCard.issue(GiftCardCode.hashOf(rawCode), GiftCardCode.last4(rawCode),
                    command.faceAmount(), now, expiresAt, command.actor(), command.memo());
            if (command.activate()) {
                card.activate();
            }
            GiftCard saved = giftCardPort.save(card);
            issued.add(new IssuedGiftCard(saved.getId(), rawCode, saved.getCodeLast4(),
                    saved.getFaceAmount()));
        }

        // 코드는 절대 로그에 남기지 않는다 — 장수와 권면가만 남긴다.
        log.info("기프트카드 발행: 장수={}, 권면가={}, 활성화={}, actor={}",
                command.quantity(), command.faceAmount(), command.activate(), command.actor());
        return issued;
    }

    /**
     * 해시가 겹치지 않는 코드를 만든다. 80비트 공간에서 충돌은 천문학적으로 드물지만,
     * 충돌하면 남의 카드를 덮어쓰므로 확인 비용을 치른다.
     */
    private String generateUnusedCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = GiftCardCode.generate();
            if (!giftCardPort.existsByCodeHash(GiftCardCode.hashOf(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("코드 생성에 반복 실패했습니다 — 난수원을 확인하십시오");
    }
}
