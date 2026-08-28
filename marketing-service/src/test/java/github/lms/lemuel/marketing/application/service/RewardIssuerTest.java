package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.out.PublishRewardRequestedPort;
import github.lms.lemuel.marketing.application.port.out.RewardGrantPort;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import github.lms.lemuel.marketing.domain.RewardStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보상 요청을 만드는 한 자리.
 *
 * <p>세 곳(일일 보상·목표 보상·럭키박스 당첨)이 같은 일을 하므로 규칙도 한 곳에 있어야 한다.
 * 여기서 확인하는 것은 셋이다 — 0원은 만들지 않고, 중복은 기존 것을 돌려주고,
 * 예약 지급은 지금 발행하지 않는다.
 */
class RewardIssuerTest {

    private static final UUID REFERENCE = UUID.randomUUID();
    private static final UUID CAMPAIGN = UUID.randomUUID();
    private static final String MEMBER = "member-1";
    private static final LocalDate EXPIRES_ON = LocalDate.of(2026, 12, 31);

    private RewardGrantPort rewardGrantPort;
    private PublishRewardRequestedPort publishPort;
    private RewardIssuer issuer;

    @BeforeEach
    void setUp() {
        rewardGrantPort = mock(RewardGrantPort.class);
        publishPort = mock(PublishRewardRequestedPort.class);
        issuer = new RewardIssuer(rewardGrantPort, publishPort);
    }

    private RewardGrant issue(BigDecimal amount, LocalDate scheduledOn) {
        return issuer.issue(RewardSource.LUCKYBOX, REFERENCE, CAMPAIGN, "8월 럭키박스", MEMBER, amount,
                EXPIRES_ON, "메모", scheduledOn);
    }

    @Test
    void 금액이_없거나_0_이하면_만들지_않는다() {
        assertNull(issue(null, null));
        assertNull(issue(BigDecimal.ZERO, null));
        assertNull(issue(new BigDecimal("-5"), null));

        verify(rewardGrantPort, never()).save(any());
        verify(publishPort, never()).rewardRequested(any(), any());
    }

    @Test
    void 즉시_지급은_저장하고_바로_발행한다() {
        when(rewardGrantPort.findByReference(RewardSource.LUCKYBOX, REFERENCE)).thenReturn(Optional.empty());
        when(rewardGrantPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RewardGrant grant = issue(new BigDecimal("500"), null);

        assertEquals(RewardStatus.REQUESTED, grant.status());
        assertEquals(REFERENCE, grant.referenceId());
        assertEquals(EXPIRES_ON, grant.expiresOn());
        assertNull(grant.scheduledOn());
        verify(publishPort).rewardRequested(grant, "8월 럭키박스");
    }

    @Test
    void 예약_지급은_저장만_하고_발행하지_않는다() {
        LocalDate scheduledOn = LocalDate.of(2026, 9, 1);
        when(rewardGrantPort.findByReference(RewardSource.LUCKYBOX, REFERENCE)).thenReturn(Optional.empty());
        when(rewardGrantPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RewardGrant grant = issue(new BigDecimal("500"), scheduledOn);

        assertEquals(RewardStatus.PENDING, grant.status());
        assertEquals(scheduledOn, grant.scheduledOn());
        verify(rewardGrantPort).save(any());
        verify(publishPort, never()).rewardRequested(any(), any());
    }

    /** 원본 한 건에 보상 한 건 — 재시도가 예외까지 가지 않게 앞단에서 거른다. */
    @Test
    void 같은_원본으로_이미_발행됐으면_그것을_그대로_돌려준다() {
        RewardGrant existing = RewardGrant.requestNow(UUID.randomUUID(), RewardSource.LUCKYBOX, REFERENCE,
                CAMPAIGN, MEMBER, new BigDecimal("500"), EXPIRES_ON, "메모");
        when(rewardGrantPort.findByReference(RewardSource.LUCKYBOX, REFERENCE)).thenReturn(Optional.of(existing));

        assertSame(existing, issue(new BigDecimal("500"), null));

        verify(rewardGrantPort, never()).save(any());
        verify(publishPort, never()).rewardRequested(any(), eq("8월 럭키박스"));
    }
}
