package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.OptionInputType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상품 → 구매자가 고를 수 있는 옵션 트리.
 *
 * <p>이 유스케이스가 존재하는 이유: 옵션 카탈로그를 읽는 길이 지금까지 {@code /admin/option-catalog}
 * (ADMIN 전용 사전)와 {@code GET /products/*&#47;variants}(ADMIN 전용 SKU 목록) 둘뿐이었다. 그래서
 * 구매자 화면은 <b>고를 것을 그릴 수가 없었고</b>, 결과적으로 모든 주문이 옵션 없는 주문으로 나갔다.
 *
 * <p><b>variantId 를 돌려주지 않는다.</b> 조합마다 SKU id 를 실어 주면 화면이 그 표를 들고
 * "선택 → SKU" 를 스스로 계산하게 되고, 그 순간 해석 규칙이 서버와 화면 두 곳에 생긴다. 이 저장소가
 * 문자열 규약({@code "색상:빨강/사이즈:L"})으로 이미 겪은 실패가 정확히 그것이라, 해석의 주인은
 * {@code POST /products/{id}/variants/resolve} 하나로 남긴다. 여기서 나가는 조합 목록은 오직
 * <b>고를 수 있는가</b>와 <b>얼마가 더 붙는가</b>를 미리 보여 주기 위한 것이다.
 *
 * <p>재고 수량도 내보내지 않는다. SKU 별 재고는 ADMIN 목록이 지키는 값이고(로그인만으로 남의 상품
 * 재고를 세게 하지 않는다), 구매자에게 필요한 것은 품절 여부라는 한 비트뿐이다.
 */
public interface DescribeProductOptionsUseCase {

    /**
     * 옵션 트리 조회. 옵션이 없는 상품이면 빈 축 목록을 돌려준다 — 없는 상품(404)과 다른 상태다.
     */
    ProductOptions describe(Long productId);

    record ProductOptions(Long productId, List<Axis> axes, List<Combination> combinations) {}

    /** 차수(sortOrder) 오름차순. 상한이 없어 3차 이상도 그대로 실린다. */
    record Axis(int sortOrder,
                String code,
                String name,
                OptionInputType inputType,
                boolean required,
                List<Value> values) {}

    record Value(String code, String name, String swatchHex, int sortOrder) {}

    /**
     * 실제로 존재하는 SKU 하나에 대응하는 조합. 카탈로그 매핑이 없는(백필 전) SKU 는 실리지 않는다 —
     * 축·값 코드를 확정할 수 없는 조합을 반쪽으로 그리면 고를 수 없는 칸이 생긴다.
     */
    record Combination(List<Selection> selections, boolean available, BigDecimal additionalPrice) {}

    record Selection(String axisCode, String valueCode) {}
}
