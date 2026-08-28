package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.LuckyboxPrize;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 경품 적재·수량 예약. */
public interface LuckyboxPrizePort {

    List<LuckyboxPrize> findByCampaign(UUID campaignId);

    Optional<LuckyboxPrize> findById(UUID prizeId);

    LuckyboxPrize save(LuckyboxPrize prize);

    /**
     * 경품 한 개를 예약한다. 성공하면 true, 이미 소진됐으면 false.
     *
     * <p>구현은 <b>조건부 UPDATE 한 문장</b>이어야 한다 — 전체 수량과 일일 수량을 WHERE 절에
     * 넣고, 갱신된 행이 0 이면 소진으로 본다. 읽어서 세고 쓰는 방식이면 확인과 차감 사이에
     * 다른 트랜잭션이 끼어들어 마지막 한 개가 두 사람에게 나간다.
     *
     * <p>레거시에는 이 확인 자체가 없었다. {@code // 아이템 수량 확인} 이라는 주석 아래가 비어
     * 있어서 "선착순 100명" 경품이 몇 명에게 나갔는지 확인할 방법조차 없었다.
     *
     * <p>실패해도 되돌릴 것이 없다 — 예약이 안 됐으니 아무것도 증가하지 않았다. 예약 이후 단계가
     * 실패하는 경우는 트랜잭션 롤백이 카운터까지 되돌린다.
     *
     * @param on 일일 수량 판정 기준일. 경품에 일일 수량이 없으면 무시된다.
     */
    boolean tryReserve(UUID prizeId, LocalDate on);
}
