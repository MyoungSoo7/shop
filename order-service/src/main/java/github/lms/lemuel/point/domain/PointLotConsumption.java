package github.lms.lemuel.point.domain;

import java.math.BigDecimal;

/**
 * 엔트리 1건이 로트 하나에서 얼마를 소비했는지.
 *
 * <p>사용 1건은 대개 여러 로트에 걸친다(만료 임박분부터 먹으므로). 이 상세가 없으면
 * 환불이 왔을 때 <b>어느 로트로 되돌려야 하는지</b> 알 수 없고, 잔고와 로트 합계의 대사도
 * 불가능해진다.
 *
 * @param lotId  소비된 로트 식별자
 * @param amount 그 로트에서 소비한 금액(양수)
 */
public record PointLotConsumption(Long lotId, BigDecimal amount) {
}
