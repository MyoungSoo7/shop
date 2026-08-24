package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.OptionAxis;
import github.lms.lemuel.product.domain.OptionAxisValue;
import github.lms.lemuel.product.domain.OptionInputType;

import java.util.List;

/**
 * 표준 옵션 축·값 카탈로그 관리.
 *
 * <p>지금까지 카탈로그를 늘리는 경로는 시드 마이그레이션과 레거시 백필뿐이었다 — 운영자가 축을
 * 하나 추가하려면 배포가 필요했다. 이 유스케이스가 그 자리를 연다.
 *
 * <p>식별은 <b>코드</b>로 한다. id 는 SKU 매핑이 붙잡는 내부 식별자이고, 운영자가 다루는 이름표는
 * 코드다. 코드는 불변이라 이름을 바꿔도 SKU·조합·주문 스냅샷이 흔들리지 않는다.
 */
public interface ManageOptionCatalogUseCase {

    /** 새 표준 축. 코드가 이미 있으면 거절한다 — 같은 축이 두 벌이 되면 파셋이 갈라진다. */
    OptionAxis createAxis(String code, String name, OptionInputType inputType);

    /** 표시 이름·표현 방식 변경. 코드는 바꿀 수 없다. */
    OptionAxis updateAxis(String code, String name, OptionInputType inputType);

    OptionAxis setAxisActive(String code, boolean active);

    List<OptionAxisValue> getValues(String axisCode);

    OptionAxisValue addValue(String axisCode, String code, String name, String swatchHex, int sortOrder);

    OptionAxisValue updateValue(String axisCode, String valueCode, String name, String swatchHex, int sortOrder);

    OptionAxisValue setValueActive(String axisCode, String valueCode, boolean active);
}
