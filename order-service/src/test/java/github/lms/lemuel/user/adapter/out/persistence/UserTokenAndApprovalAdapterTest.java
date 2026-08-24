package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.MembershipAction;
import github.lms.lemuel.user.domain.MembershipApproval;
import github.lms.lemuel.user.domain.PasswordResetToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PasswordResetToken / MembershipApproval 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속).
 */
@ExtendWith(MockitoExtension.class)
class UserTokenAndApprovalAdapterTest {

    // ── PasswordResetTokenPersistenceAdapter ──────────────────────

    @Mock SpringDataPasswordResetTokenRepository tokenRepository;

    private PasswordResetTokenPersistenceAdapter tokenAdapter() {
        return new PasswordResetTokenPersistenceAdapter(tokenRepository, new PasswordResetTokenMapperImpl());
    }

    private PasswordResetTokenJpaEntity tokenEntity() {
        return new PasswordResetTokenJpaEntity(1L, 100L, "tok-123",
                LocalDateTime.now().plusMinutes(30), false, LocalDateTime.now());
    }

    @Test
    @DisplayName("token save: 저장 후 도메인 매핑")
    void tokenSave() {
        when(tokenRepository.save(any())).thenReturn(tokenEntity());
        PasswordResetToken saved = tokenAdapter().save(
                PasswordResetToken.create(100L, 30, LocalDateTime.of(2026, 3, 1, 0, 0)));
        assertThat(saved.getToken()).isEqualTo("tok-123");
    }

    @Test
    @DisplayName("findByToken: 조회 매핑")
    void findByToken() {
        when(tokenRepository.findByToken("tok-123")).thenReturn(Optional.of(tokenEntity()));
        assertThat(tokenAdapter().findByToken("tok-123")).isPresent();
    }

    @Test
    @DisplayName("findValidTokenByUserId: 미사용·미만료 토큰 조회")
    void findValidTokenByUserId() {
        when(tokenRepository.findByUserIdAndUsedFalseAndExpiryDateAfter(eq(100L), any()))
                .thenReturn(Optional.of(tokenEntity()));
        assertThat(tokenAdapter().findValidTokenByUserId(100L)).isPresent();
    }

    @Test
    @DisplayName("deleteExpiredTokens: 만료 토큰 일괄 삭제 위임")
    void deleteExpiredTokens() {
        tokenAdapter().deleteExpiredTokens();
        verify(tokenRepository).deleteByExpiryDateBefore(any());
    }

    // ── MembershipApprovalPersistenceAdapter ──────────────────────

    @Mock SpringDataMembershipApprovalRepository approvalRepository;

    @Test
    @DisplayName("approval save: 엔티티 저장 후 도메인에 id 부여")
    void approvalSave() {
        MembershipApprovalJpaEntity savedEntity = new MembershipApprovalJpaEntity();
        savedEntity.setId(55L);
        when(approvalRepository.save(any())).thenReturn(savedEntity);

        MembershipApprovalPersistenceAdapter adapter =
                new MembershipApprovalPersistenceAdapter(approvalRepository);
        MembershipApproval approval =
                new MembershipApproval(100L, MembershipAction.APPROVE, "정상 승인", 99L);

        MembershipApproval result = adapter.save(approval);

        assertThat(result.getId()).isEqualTo(55L);
        verify(approvalRepository).save(any(MembershipApprovalJpaEntity.class));
    }
}
