package github.lms.lemuel.giftcard.application.port.in;

import java.math.BigDecimal;

/**
 * 기프트카드 등록(귀속) 유스케이스.
 *
 * <p>{@code userId} 는 반드시 <b>JWT 주체에서 파생</b>해야 한다. 요청 본문의 userId 를 믿으면
 * 코드를 아는 사람이 남의 계정으로 등록시킬 수 있다.
 *
 * <p>등록은 부채가 생기는 지점이라 GL 이벤트를 낸다.
 */
public interface RegisterGiftCardUseCase {

    record RegisterGiftCardCommand(String rawCode, Long userId, String actor) {
    }

    record RegisterGiftCardResult(Long giftCardId, String codeLast4, BigDecimal faceAmount,
                                  BigDecimal totalBalance) {
    }

    RegisterGiftCardResult register(RegisterGiftCardCommand command);
}
