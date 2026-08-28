package github.lms.lemuel.sellertier.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 셀러 등급 명부 조회 (ADR 0031).
 *
 * <p><b>왜 필요한가</b>: 등급 콘솔에는 지금까지 <b>바꾸는 길만</b> 있었다 — 재산정·관리자 지정·정합 검사.
 * 정작 "누가 몇 등급인가"를 답하는 조회가 없어서, 관리자 지정은 셀러 ID 를 이미 알고 있어야만
 * 쓸 수 있었고(화면의 입력란이 숫자다) 등급 분포를 확인할 방법은 DB 밖에 없었다.
 * 바꿀 수는 있는데 볼 수는 없는 콘솔은 사실상 쓸 수 없는 콘솔이다.
 *
 * <p>읽기 전용이다. 재산정과 달리 아무 것도 판정하지 않는다 — 지금 저장된 값을 그대로 보여준다.
 *
 * <p>등급 문자열을 파싱하지 않고 원본 그대로 싣는 것은 {@code TierCacheDrift} 와 같은 이유다.
 * enum 밖의 값이 들어 있다면 그 자체가 조사 대상인데, 여기서 valueOf 로 터뜨리면 명부가 통째로
 * 뜨지 않아 이상 행 하나가 나머지 전부를 가린다.
 */
public interface ListSellerTiersUseCase {

    /** @param limit 명부 상한 — 전수 스캔이 운영 DB 를 오래 잡지 않게 한다 */
    SellerTierRoster list(LocalDate today, int limit);

    /**
     * 명부 한 행.
     *
     * @param sellerId             셀러(=상품 소유자) id
     * @param email                셀러 계정 이메일 — 숫자 id 만으로는 누구인지 알 수 없다
     * @param name                 표시 이름. 없을 수 있다
     * @param tier                 정본 등급({@code seller_tier_assignment}). 한 번도 산정되지 않았으면 null
     * @param cachedTier           읽기 캐시({@code users.seller_tier}) — 결제가 실제로 싣는 값
     * @param effectiveFrom        정본 적용일
     * @param demotionGuardUntil   이 날짜까지는 자동 재산정이 강등하지 못한다
     * @param consecutiveMissCount 연속 미달 횟수 — 다음 재산정에서 내려갈지 예측하는 근거
     * @param netSales12m          최근 12개월 결제 순액(환불 차감). 재산정이 쓰는 것과 같은 집계
     * @param productCount         등록 상품 수 — 등급이 붙어 있는데 0 이면 그 자체가 확인 대상이다
     * @param mismatched           정본↔캐시 불일치. 결제는 캐시값으로 정산을 확정하므로 이 행은 돈이 어긋난다
     */
    record SellerTierRow(Long sellerId, String email, String name,
                         String tier, String cachedTier,
                         LocalDate effectiveFrom, LocalDate demotionGuardUntil,
                         int consecutiveMissCount, BigDecimal netSales12m,
                         long productCount, boolean mismatched) { }

    /**
     * @param rows      명부. {@code total} 보다 적을 수 있다(상한에 잘린 경우)
     * @param total     전체 셀러 수 — 상한에 잘려도 규모는 정확히 보고한다
     * @param truncated 상한에 잘렸는가. 화면이 "이게 전부"라고 오해하지 않게 명시한다
     */
    record SellerTierRoster(List<SellerTierRow> rows, long total, boolean truncated) { }
}
