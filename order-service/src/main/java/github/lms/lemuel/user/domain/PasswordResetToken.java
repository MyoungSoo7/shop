package github.lms.lemuel.user.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 비밀번호 재설정 토큰 도메인
 */
public class PasswordResetToken {

    private Long id;
    private Long userId;
    private String token;
    private LocalDateTime expiryDate;
    private boolean used;
    private LocalDateTime createdAt;

    public PasswordResetToken() {
        this.token = UUID.randomUUID().toString();
        this.used = false;
        this.createdAt = LocalDateTime.now();
    }

    public PasswordResetToken(Long id, Long userId, String token, LocalDateTime expiryDate,
                              boolean used, LocalDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.token = token;
        this.expiryDate = expiryDate;
        this.used = used;
        this.createdAt = createdAt;
    }

    /**
     * 발급 시각 {@code now} 를 주입받아 만료 시각을 계산한다 — 시스템 시계를 직접 읽지 않는다.
     * 응용 서비스가 KST {@code Clock} 으로 만든 시각을 넘기므로 발급/만료가 같은 시간축 위에 놓인다.
     */
    public static PasswordResetToken create(Long userId, int expiryMinutes, LocalDateTime now) {
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.userId = userId;
        resetToken.createdAt = now;
        resetToken.expiryDate = now.plusMinutes(expiryMinutes);
        return resetToken;
    }

    /**
     * 만료 여부. 판정 시각 {@code now} 는 호출자가 주입한다 — 도메인이 시스템 시계를 직접 읽지 않는다.
     * 경계는 포함이다({@code now == expiryDate} 이면 아직 유효).
     */
    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(this.expiryDate);
    }

    /** 미사용 + 미만료. 판정 시각은 {@link #isExpired(LocalDateTime)} 과 동일하게 주입받는다. */
    public boolean isValid(LocalDateTime now) {
        return !used && !isExpired(now);
    }

    public void markAsUsed() {
        this.used = true;
    }

    /** DB 부여 PK 주입(setter 대체). 전체 필드 복원은 전체 생성자 사용. */
    public void assignId(Long id) {
        this.id = id;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public LocalDateTime getExpiryDate() {
        return expiryDate;
    }

    public boolean isUsed() {
        return used;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
