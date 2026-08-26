package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.GiftClaim;
import github.lms.lemuel.order.domain.GiftClaimStatus;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 선물 받기 — 받는 사람 쪽 진입점. <b>로그인하지 않은 사람이 호출한다.</b>
 *
 * <p>그래서 모든 메서드가 토큰 하나만 받는다. 회원가입을 요구하면 "주소를 주기 싫어서" 대신
 * "가입하기 싫어서" 선물을 못 받게 될 뿐이라 문제가 그대로다.
 *
 * <p>인가가 토큰 하나에 걸려 있으므로 밖으로 나가는 값은 최소한이어야 한다 —
 * {@link GiftView} 에 <b>금액이 없는</b> 이유다(받는 사람에게 선물 가격을 보여 줄 이유도 없다).
 */
public interface ClaimGiftUseCase {

    /** 링크를 열었을 때 보이는 화면의 재료. 상태 변화는 없다. */
    GiftView view(String token);

    /** 인증번호를 발송한다(이전 번호는 그 자리에서 죽는다). */
    void requestVerificationCode(String token);

    /** 인증번호 확인. 틀리면 예외 — 남은 시도 횟수가 메시지에 담긴다. */
    void verify(String token, String code);

    /** 받는 사람이 자기 배송지를 낸다. 여기서 주문에 배송지가 붙고 배송이 시작된다. */
    void submitAddress(String token, AddressSubmission address);

    /**
     * 받는 사람이 내는 배송지.
     *
     * <p>{@link ShippingAddressSnapshot} 을 직접 받지 않는 이유는 그쪽이 <b>수령인 이름을 필수로</b>
     * 요구하기 때문이다. 여기서는 이름이 선택이다 — 화면에 이미 자기 이름이 적혀 있어 다시 받는
     * 것이 어색하고, 비우면 링크에 적힌 이름을 쓴다. 그 채워 넣기를 웹 계층이 하면 "이름이 비면
     * 무엇이 되는가"라는 규칙이 어댑터로 새어 나간다.
     *
     * @param recipientName 비면(null·공백) 선물에 적힌 받는 사람 이름을 쓴다
     */
    record AddressSubmission(String recipientName,
                             String phone,
                             String postalCode,
                             String address1,
                             String address2,
                             String deliveryMemo) {
    }

    /** 기한이 지난 링크를 EXPIRED 로 닫는다(배치). 판정 자체는 시각이 하고, 이건 기록이다. */
    int expireOverdue(LocalDateTime now, int batchSize);

    /**
     * 받는 사람에게 보이는 것 전부.
     *
     * @param actionable  지금 무언가 할 수 있는가 — 만료·종단이면 false
     * @param maskedPhone 인증번호가 어디로 가는지 확인시켜 주되 온전한 번호는 주지 않는다
     */
    record GiftView(Long orderId,
                    GiftClaimStatus status,
                    boolean actionable,
                    String recipientName,
                    String maskedPhone,
                    String message,
                    LocalDateTime expiresAt,
                    List<Item> items) {

        public static GiftView of(GiftClaim claim, LocalDateTime now, List<Item> items) {
            return new GiftView(claim.getOrderId(), claim.getStatus(), claim.isActionable(now),
                    claim.getRecipientName(), claim.maskedRecipientPhone(), claim.getMessage(),
                    claim.getExpiresAt(), items);
        }
    }

    /** 단가·합계는 담지 않는다. 무엇을 받는지만 보이면 된다. */
    record Item(String productName, int quantity) {
    }
}
