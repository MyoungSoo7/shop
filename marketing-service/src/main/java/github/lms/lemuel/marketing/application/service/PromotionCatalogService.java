package github.lms.lemuel.marketing.application.service;

import github.lms.lemuel.marketing.application.port.dto.PromotionKind;
import github.lms.lemuel.marketing.application.port.dto.PromotionSummary;
import github.lms.lemuel.marketing.application.port.in.ViewPromotionsUseCase;
import github.lms.lemuel.marketing.application.port.out.LoadAttendanceCampaignPort;
import github.lms.lemuel.marketing.application.port.out.LoadLuckyboxCampaignPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 진행 중인 프로모션 통합 목록.
 *
 * <p>출석과 럭키박스를 한 화면에 합쳐 보여 주는 자리다. 저장은 따로, 합치는 것은 조회에서.
 * 공통 상위 테이블을 두지 않은 이유는 {@code V1__marketing_core.sql} 머리말에 적어 두었다.
 */
@Service
public class PromotionCatalogService implements ViewPromotionsUseCase {

    private final LoadAttendanceCampaignPort attendancePort;
    private final LoadLuckyboxCampaignPort luckyboxPort;

    public PromotionCatalogService(LoadAttendanceCampaignPort attendancePort,
                                   LoadLuckyboxCampaignPort luckyboxPort) {
        this.attendancePort = attendancePort;
        this.luckyboxPort = luckyboxPort;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PromotionSummary> runningOn(LocalDate on) {
        List<PromotionSummary> merged = new ArrayList<>();
        attendancePort.findRunningOn(on).forEach(c -> merged.add(new PromotionSummary(
                PromotionKind.ATTENDANCE, c.id(), c.name(), c.startsOn(), c.endsOn(),
                c.banner().pcImageUrl(), c.banner().mobileImageUrl())));
        luckyboxPort.findRunningOn(on).forEach(c -> merged.add(new PromotionSummary(
                PromotionKind.LUCKYBOX, c.id(), c.name(), c.startsOn(), c.endsOn(),
                c.banner().pcImageUrl(), c.banner().mobileImageUrl())));

        // 곧 끝나는 것부터. 배너 순서를 운영자가 못 정한다는 뜻이라 나중에 정렬 컬럼이 필요해질 수
        // 있지만, 레거시에도 없던 기능이라 여기서 만들지 않았다.
        merged.sort(Comparator.comparing(PromotionSummary::endsOn).thenComparing(PromotionSummary::name));
        return merged;
    }
}
