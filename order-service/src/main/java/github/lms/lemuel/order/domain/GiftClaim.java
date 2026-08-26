package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.InvalidGiftClaimStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 선물 수령 — <b>배송지를 받는 사람이 직접 낸다</b>는 사실의 기록.
 *
 * <p>이게 왜 따로 필요한가. 지금까지 선물을 보내려면 보내는 사람이 받는 사람의 <b>집 주소를 알아야</b>
 * 했다. 그래서 실무에서는 "주소 좀 알려줘"를 카톡으로 묻고, 받은 주소를 주문서에 옮겨 적었다.
 * 그 순간 주소는 두 사람의 대화창과 주문서에 각각 한 벌씩 남고, 오타가 나면 어느 쪽이 맞는지
 * 판단할 근거가 없다. 무엇보다 <b>주기 싫은 주소를 줘야 선물을 받을 수 있었다.</b>
 *
 * <p>여기서는 보내는 사람이 <b>휴대폰 번호만</b> 알면 된다. 받는 사람이 링크로 들어와 본인확인을
 * 하고 자기 주소를 넣으면, 그때 주문에 배송지가 붙는다({@link Order#attachShippingAddress}).
 * 주문서의 배송지가 여전히 "한 번만 쓰인다"는 성질을 지키는 것이 중요하다 — 다만 그것을 적는
 * 사람이 사는 쪽이 아니라 받는 쪽일 뿐이다.
 *
 * <p><b>링크 토큰의 평문을 들고 있지 않는다.</b> 이 레코드는 해시만 보관하고, 평문은 발급 순간
 * 딱 한 번 호출자에게 돌려주고 잊는다. DB 한 벌이 새면 그 안의 링크가 전부 즉시 쓸 수 있는
 * 상태여서는 안 된다 — 이 링크는 로그인 없이 남의 주문 화면을 여는 열쇠다.
 * (기존 {@code PasswordResetToken} 은 평문을 저장한다. 그 자리를 따라가지 않는다.)
 */
public class GiftClaim {

    /** 인증번호를 몇 번 틀리면 그 번호를 버리는가. 링크를 주운 사람이 6자리를 다 훑지 못하게 한다. */
    public static final int MAX_VERIFY_ATTEMPTS = 5;

    private static final int MAX_NAME = 60;
    private static final int MAX_PHONE = 40;
    private static final int MAX_MESSAGE = 200;

    private Long id;
    private final Long orderId;
    private final Long senderUserId;

    private final String recipientName;
    private final String recipientPhone;
    private final String message;

    /** 재발송 때 갈아 끼우므로 final 이 아니다 — {@link #rotateToken}. */
    private String tokenHash;
    private GiftClaimStatus status;

    private String verificationCodeHash;
    private LocalDateTime codeExpiresAt;
    private int verifyAttempts;

    private final LocalDateTime expiresAt;
    private final LocalDateTime createdAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime claimedAt;
    private LocalDateTime updatedAt;

    // ───────── 생성 ─────────

    /**
     * 선물을 보낸다.
     *
     * @param tokenHash 링크 토큰의 해시. 평문은 여기 들어오지 않는다 — 호출자가 발급하고 흘려보낸다.
     * @param expiresAt 링크 유효기한. 무기한 링크는 시간이 지날수록 새어 나갈 확률만 커진다.
     */
    public static GiftClaim open(Long orderId,
                                 Long senderUserId,
                                 String recipientName,
                                 String recipientPhone,
                                 String message,
                                 String tokenHash,
                                 LocalDateTime now,
                                 LocalDateTime expiresAt) {
        if (orderId == null) throw new OrderInvariantViolationException("orderId 필수");
        if (senderUserId == null) throw new OrderInvariantViolationException("보낸 사람 필수");
        if (now == null || expiresAt == null) {
            throw new OrderInvariantViolationException("발급·만료 시각 필수");
        }
        if (!expiresAt.isAfter(now)) {
            throw new OrderInvariantViolationException("이미 지난 시각으로는 선물 링크를 만들 수 없습니다");
        }
        return new GiftClaim(null, orderId, senderUserId,
                requireText(recipientName, "받는 사람 이름", MAX_NAME),
                requirePhone(recipientPhone),
                trimToNull(message, "선물 메시지", MAX_MESSAGE),
                requireText(tokenHash, "링크 토큰", 128),
                GiftClaimStatus.PENDING,
                null, null, 0,
                expiresAt, now, null, null, now);
    }

    /** 영속 계층 복원 전용 — 검증 없이 있는 그대로 되살린다. */
    public static GiftClaim restore(Long id, Long orderId, Long senderUserId,
                                    String recipientName, String recipientPhone, String message,
                                    String tokenHash, GiftClaimStatus status,
                                    String verificationCodeHash, LocalDateTime codeExpiresAt,
                                    int verifyAttempts,
                                    LocalDateTime expiresAt, LocalDateTime createdAt,
                                    LocalDateTime verifiedAt, LocalDateTime claimedAt,
                                    LocalDateTime updatedAt) {
        return new GiftClaim(id, orderId, senderUserId, recipientName, recipientPhone, message,
                tokenHash, status, verificationCodeHash, codeExpiresAt, verifyAttempts,
                expiresAt, createdAt, verifiedAt, claimedAt, updatedAt);
    }

    private GiftClaim(Long id, Long orderId, Long senderUserId,
                      String recipientName, String recipientPhone, String message,
                      String tokenHash, GiftClaimStatus status,
                      String verificationCodeHash, LocalDateTime codeExpiresAt, int verifyAttempts,
                      LocalDateTime expiresAt, LocalDateTime createdAt,
                      LocalDateTime verifiedAt, LocalDateTime claimedAt, LocalDateTime updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.senderUserId = senderUserId;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.message = message;
        this.tokenHash = tokenHash;
        this.status = status;
        this.verificationCodeHash = verificationCodeHash;
        this.codeExpiresAt = codeExpiresAt;
        this.verifyAttempts = verifyAttempts;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.verifiedAt = verifiedAt;
        this.claimedAt = claimedAt;
        this.updatedAt = updatedAt;
    }

    // ───────── 진행 ─────────

    /**
     * 링크를 새 토큰으로 갈아 끼운다(재발송).
     *
     * <p><b>옛 링크는 그 자리에서 죽는다.</b> 살려 두면 첫 발송이 잘못된 번호로 갔을 때 —
     * 재발송을 누르는 가장 흔한 이유다 — 엉뚱한 사람 손의 링크가 그대로 유효하게 남는다.
     *
     * <p>인증번호도 함께 버린다. 번호 해시는 토큰 해시를 소금으로 묶여 있어 새 토큰에서는
     * 어차피 맞지 않는다. 남겨 두면 "맞는 번호를 넣었는데 틀리다고 나오는" 상태만 만든다.
     *
     * <p>기한이 지났거나 끝난 선물은 재발송할 수 없다 — 그건 새로 보내야 하는 것이다.
     */
    public void rotateToken(String newTokenHash, LocalDateTime now) {
        requireUsable(now);
        if (newTokenHash == null || newTokenHash.isBlank()) {
            throw new OrderInvariantViolationException("링크 토큰 필수");
        }
        this.tokenHash = newTokenHash;
        this.verificationCodeHash = null;
        this.codeExpiresAt = null;
        this.verifyAttempts = 0;
        touch(now);
    }

    /**
     * 인증번호를 새로 낸다. 이전 번호는 그 자리에서 죽는다 — 두 번호가 동시에 유효하면
     * 재발송이 곧 시도 횟수 초기화가 되어 {@link #MAX_VERIFY_ATTEMPTS} 가 무의미해진다.
     */
    public void issueVerificationCode(String codeHash, LocalDateTime now, LocalDateTime codeExpiresAt) {
        requireUsable(now);
        if (status != GiftClaimStatus.PENDING) {
            throw new InvalidGiftClaimStateException(status, "본인확인이 끝난 선물입니다");
        }
        if (codeHash == null || codeHash.isBlank()) {
            throw new OrderInvariantViolationException("인증번호 필수");
        }
        if (codeExpiresAt == null || !codeExpiresAt.isAfter(now)) {
            throw new OrderInvariantViolationException("인증번호 유효시각이 이미 지났습니다");
        }
        this.verificationCodeHash = codeHash;
        this.codeExpiresAt = codeExpiresAt;
        this.verifyAttempts = 0;
        touch(now);
    }

    /**
     * 받는 사람이 인증번호를 낸다.
     *
     * <p>틀리면 시도 횟수만 올리고 예외를 던진다 — <b>호출자는 이때도 저장해야 한다.</b> 저장하지
     * 않으면 롤백으로 카운터가 되돌아가 무제한 대입이 된다.
     */
    public void verify(String codeHash, LocalDateTime now) {
        requireUsable(now);
        if (status != GiftClaimStatus.PENDING) {
            throw new InvalidGiftClaimStateException(status, "이미 본인확인이 끝났습니다");
        }
        if (verificationCodeHash == null) {
            throw new InvalidGiftClaimStateException(status, "발송된 인증번호가 없습니다");
        }
        if (codeExpiresAt == null || now.isAfter(codeExpiresAt)) {
            throw new InvalidGiftClaimStateException(status, "인증번호 유효시간이 지났습니다 — 다시 받아 주세요");
        }
        if (verifyAttempts >= MAX_VERIFY_ATTEMPTS) {
            throw new InvalidGiftClaimStateException(status,
                    "인증번호를 너무 많이 틀렸습니다 — 다시 받아 주세요");
        }
        if (!constantTimeEquals(verificationCodeHash, codeHash)) {
            this.verifyAttempts++;
            touch(now);
            // 남은 횟수를 알려 준다 — 안 알려 주면 몇 번째에 잠기는지 몰라 문의로 온다.
            throw new InvalidGiftClaimStateException(status,
                    "인증번호가 맞지 않습니다 (남은 시도 " + (MAX_VERIFY_ATTEMPTS - verifyAttempts) + "회)");
        }
        this.status = GiftClaimStatus.VERIFIED;
        this.verifiedAt = now;
        // 쓴 번호는 즉시 버린다. 인증이 끝난 뒤에도 남겨 두면 유출 시 재사용된다.
        this.verificationCodeHash = null;
        this.codeExpiresAt = null;
        touch(now);
    }

    /** 받는 사람이 배송지를 냈다. 주문에 주소를 붙이는 것은 호출자(응용 서비스)의 몫이다. */
    public void markClaimed(LocalDateTime now) {
        requireUsable(now);
        transitionTo(GiftClaimStatus.CLAIMED, now);
        this.claimedAt = now;
    }

    /** 보낸 사람이 거둬들이거나 주문이 취소됐다. */
    public void cancel(LocalDateTime now) {
        transitionTo(GiftClaimStatus.CANCELED, now);
    }

    /** 기한이 지난 링크를 닫는다(배치·조회 시점 어디서 불러도 같은 결과). */
    public void expire(LocalDateTime now) {
        transitionTo(GiftClaimStatus.EXPIRED, now);
    }

    private void transitionTo(GiftClaimStatus target, LocalDateTime now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidGiftClaimStateException(status,
                    "허용되지 않은 수령 상태 전이: " + status + " → " + target);
        }
        this.status = target;
        touch(now);
    }

    /**
     * 아직 쓸 수 있는 링크인지 — 만료는 상태 전이 없이도 <b>시각만으로</b> 판정된다.
     *
     * <p>배치가 EXPIRED 로 바꿔 주기를 기다리지 않는 이유: 그 배치가 늦거나 멈추면 기한이 지난
     * 링크가 그동안 살아 있게 된다. 만료는 기록이 아니라 사실이어야 한다.
     */
    private void requireUsable(LocalDateTime now) {
        if (status.isTerminal()) {
            throw new InvalidGiftClaimStateException(status, "이미 끝난 선물입니다");
        }
        if (isExpired(now)) {
            throw new InvalidGiftClaimStateException(status, "선물 링크의 유효기간이 지났습니다");
        }
    }

    // ───────── 조회 ─────────

    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(expiresAt);
    }

    /** 받는 사람이 지금 무언가 할 수 있는가 — 화면의 안내 문구 근거. */
    public boolean isActionable(LocalDateTime now) {
        return status.isOpen() && !isExpired(now);
    }

    /**
     * 화면에 보일 수령자 연락처 — 가운데를 가린다.
     *
     * <p>이 링크는 로그인 없이 열린다. 링크를 주운 사람에게 온전한 번호를 보여 주면 링크 유출이
     * 곧 개인정보 유출이 된다. 그렇다고 아예 안 보여 주면 받는 사람이 "내 번호가 맞나"를 확인할
     * 수 없어 인증번호를 어디로 받는지 모른 채 누르게 된다.
     */
    public String maskedRecipientPhone() {
        String digits = recipientPhone.replaceAll("\\D", "");
        if (digits.length() < 7) {
            return "***";
        }
        return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
    }

    public void assignId(Long assignedId) {
        if (this.id != null) {
            throw new OrderInvariantViolationException("이미 식별자가 있는 선물입니다");
        }
        this.id = assignedId;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getSenderUserId() { return senderUserId; }
    public String getRecipientName() { return recipientName; }
    public String getRecipientPhone() { return recipientPhone; }
    public String getMessage() { return message; }
    public String getTokenHash() { return tokenHash; }
    public GiftClaimStatus getStatus() { return status; }
    public String getVerificationCodeHash() { return verificationCodeHash; }
    public LocalDateTime getCodeExpiresAt() { return codeExpiresAt; }
    public int getVerifyAttempts() { return verifyAttempts; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    private void touch(LocalDateTime now) {
        this.updatedAt = now;
    }

    /**
     * 해시 비교에서 앞부분만 맞아도 빨리 빠져나오지 않는다. 6자리 숫자라 실익은 작지만,
     * 비교 대상이 비밀이면 상수 시간으로 비교하는 것이 기본값이어야 한다.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }

    private static String requirePhone(String value) {
        String trimmed = requireText(value, "받는 사람 휴대폰", MAX_PHONE);
        if (trimmed.replaceAll("\\D", "").length() < 9) {
            throw new OrderInvariantViolationException("받는 사람 휴대폰 번호가 올바르지 않습니다");
        }
        return trimmed;
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new OrderInvariantViolationException(label + " 필수");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new OrderInvariantViolationException(label + " 는 " + maxLength + "자를 넘을 수 없습니다");
        }
        return trimmed;
    }

    private static String trimToNull(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new OrderInvariantViolationException(label + " 는 " + maxLength + "자를 넘을 수 없습니다");
        }
        return trimmed;
    }
}
