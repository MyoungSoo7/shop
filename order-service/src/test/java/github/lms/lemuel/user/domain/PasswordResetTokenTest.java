package github.lms.lemuel.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class PasswordResetTokenTest {

    @Test @DisplayName("기본 생성자: UUID 토큰과 기본값 설정")
    void defaultConstructor() {
        var token = new PasswordResetToken();
        assertThat(token.getToken()).isNotNull().isNotBlank();
        assertThat(token.isUsed()).isFalse();
        assertThat(token.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("create: userId와 만료 시간 설정")
    void create() {
        var token = PasswordResetToken.create(42L, 30, T);
        assertThat(token.getUserId()).isEqualTo(42L);
        assertThat(token.getToken()).isNotBlank();
        assertThat(token.isUsed()).isFalse();
        assertThat(token.getExpiryDate()).isAfter(T.plusMinutes(29));
    }

    @Test @DisplayName("isValid: 미사용 + 미만료이면 true")
    void isValid_true() {
        var token = PasswordResetToken.create(1L, 30, T);
        assertThat(token.isValid(T.plusMinutes(29))).isTrue();
    }

    @Test @DisplayName("isValid: 사용됨이면 false")
    void isValid_used() {
        var token = PasswordResetToken.create(1L, 30, T);
        token.markAsUsed();
        assertThat(token.isValid(T)).isFalse();
        assertThat(token.isUsed()).isTrue();
    }

    @Test @DisplayName("isExpired: 만료된 토큰")
    void isExpired() {
        var token = new PasswordResetToken(1L, 1L, "tok", T.minusMinutes(1), false, T.minusMinutes(31));
        assertThat(token.isExpired(T)).isTrue();
        assertThat(token.isValid(T)).isFalse();
    }

    // ── 만료 판정은 시스템 시계가 아니라 주입된 시각을 따른다 ──────────────────────────

    private static final LocalDateTime T = LocalDateTime.of(2026, 3, 1, 0, 0, 0);

    @Test @DisplayName("만료 경계: 만료 시각과 정확히 같은 순간은 아직 유효")
    void isExpired_atExactExpiry_false() {
        var token = new PasswordResetToken(1L, 1L, "tok", T, false, T.minusMinutes(30));
        assertThat(token.isExpired(T)).isFalse();
        assertThat(token.isValid(T)).isTrue();
    }

    @Test @DisplayName("만료 경계: 만료 시각 1나노 뒤는 만료")
    void isExpired_oneNanoAfterExpiry_true() {
        var token = new PasswordResetToken(1L, 1L, "tok", T, false, T.minusMinutes(30));
        assertThat(token.isExpired(T.plusNanos(1))).isTrue();
        assertThat(token.isValid(T.plusNanos(1))).isFalse();
    }

    @Test @DisplayName("create: 주입된 시각 기준으로 만료 시각을 정확히 계산")
    void create_withInjectedNow_setsExactExpiry() {
        var token = PasswordResetToken.create(42L, 30, T);
        assertThat(token.getExpiryDate()).isEqualTo(T.plusMinutes(30));
        assertThat(token.getCreatedAt()).isEqualTo(T);
    }

    @Test @DisplayName("만료 판정은 시스템 시계에 의존하지 않는다 — 오래전 토큰도 그 이전 시각으로 판정하면 유효")
    void isValid_doesNotDependOnSystemClock() {
        var expiry = LocalDateTime.of(2020, 1, 1, 0, 30);
        var token = new PasswordResetToken(1L, 1L, "tok", expiry, false, expiry.minusMinutes(30));
        assertThat(token.isValid(LocalDateTime.of(2020, 1, 1, 0, 10))).isTrue();
    }

    @Test @DisplayName("사용된 토큰은 미만료여도 무효")
    void isValid_usedTokenInvalid() {
        var token = new PasswordResetToken(1L, 1L, "tok", T, true, T.minusMinutes(30));
        assertThat(token.isValid(T)).isFalse();
    }

    @Test @DisplayName("전체 생성자: 모든 필드 설정")
    void fullConstructor() {
        var now = LocalDateTime.of(2025, 1, 1, 0, 0);
        var expiry = LocalDateTime.of(2025, 1, 1, 1, 0);
        var token = new PasswordResetToken(1L, 2L, "abc", expiry, true, now);
        assertThat(token.getId()).isEqualTo(1L);
        assertThat(token.getUserId()).isEqualTo(2L);
        assertThat(token.getToken()).isEqualTo("abc");
        assertThat(token.getExpiryDate()).isEqualTo(expiry);
        assertThat(token.isUsed()).isTrue();
        assertThat(token.getCreatedAt()).isEqualTo(now);
    }

    @Test @DisplayName("assignId: id 설정")
    void setter() {
        var token = new PasswordResetToken();
        token.assignId(99L);
        assertThat(token.getId()).isEqualTo(99L);
    }
}
