package github.lms.lemuel.giftcard.application.service;

import github.lms.lemuel.giftcard.application.port.in.RegisterGiftCardUseCase;
import github.lms.lemuel.giftcard.application.port.out.GiftCardEntryPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.application.port.out.PublishGiftCardEventPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardCode;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import github.lms.lemuel.giftcard.domain.GiftCardSelector;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 기프트카드 등록 — 코드를 내 지갑에 넣는다. <b>부채가 생기는 유일한 지점</b>이다.
 *
 * <p>실패는 전부 같은 메시지로 답한다. "그런 코드 없음"과 "이미 등록됨"을 구분해 알려 주면,
 * 코드를 두드리는 쪽에 <b>유효한 코드가 존재한다</b>는 정보를 주게 된다.
 */
@Service
@Transactional
public class RegisterGiftCardService implements RegisterGiftCardUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterGiftCardService.class);

    private static final String REFERENCE_TYPE = "REGISTRATION";

    private final GiftCardPort giftCardPort;
    private final GiftCardEntryPort entryPort;
    private final PublishGiftCardEventPort eventPort;

    public RegisterGiftCardService(GiftCardPort giftCardPort, GiftCardEntryPort entryPort,
                                   PublishGiftCardEventPort eventPort) {
        this.giftCardPort = giftCardPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public RegisterGiftCardResult register(RegisterGiftCardCommand command) {
        if (command.userId() == null) {
            throw new InvalidGiftCardStateException("등록 주체가 없습니다", "NONE", "register");
        }
        String codeHash = GiftCardCode.hashOf(command.rawCode());

        GiftCard card = giftCardPort.loadByCodeHashForUpdate(codeHash)
                .orElseThrow(RegisterGiftCardService::rejected);
        if (!card.getStatus().isRegisterable()) {
            // 이미 등록됐는지, 정지됐는지, 만료됐는지 구분해 알려 주지 않는다.
            throw rejected();
        }
        if (card.isExpiredAt(OffsetDateTime.now())) {
            throw rejected();
        }

        card.registerTo(command.userId(), OffsetDateTime.now());
        GiftCard saved = giftCardPort.save(card);

        int sequence = entryPort.nextSequence(saved.getId(), GiftCardEntryType.REGISTER,
                REFERENCE_TYPE, String.valueOf(command.userId()));
        GiftCardEntry entry = entryPort.append(GiftCardEntry.register(saved.getId(),
                saved.getFaceAmount(), REFERENCE_TYPE, String.valueOf(command.userId()),
                sequence, command.actor(), saved.getMemo()));
        eventPort.giftCardRegistered(saved, entry);

        // 코드도, 해시도 로그에 남기지 않는다.
        log.info("기프트카드 등록: cardId={}, userId={}, 권면가={}",
                saved.getId(), command.userId(), saved.getFaceAmount());

        return new RegisterGiftCardResult(saved.getId(), saved.getCodeLast4(), saved.getFaceAmount(),
                GiftCardSelector.spendableBalance(giftCardPort.loadSpendableReadOnly(command.userId())));
    }

    /** 실패 사유를 구분하지 않는 단일 응답 — 코드 존재 여부를 흘리지 않기 위해서다. */
    private static InvalidGiftCardStateException rejected() {
        return new InvalidGiftCardStateException(
                "사용할 수 없는 기프트카드 코드입니다", "UNKNOWN", "register");
    }
}
