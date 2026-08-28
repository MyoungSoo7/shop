package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.LuckyboxDraw;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 참여·당첨 기록 적재·저장.
 *
 * <p>{@link #save}는 {@code (campaign_id, member_ref, entry_slot)} 유니크 제약 위반을
 * {@link github.lms.lemuel.marketing.domain.exception.AlreadyParticipatedException} 로 바꿔 던진다.
 */
public interface LuckyboxDrawPort {

    Optional<LuckyboxDraw> findBySlot(UUID campaignId, String memberRef, String entrySlot);

    List<LuckyboxDraw> findByMember(UUID campaignId, String memberRef);

    LuckyboxDraw save(LuckyboxDraw draw);
}
