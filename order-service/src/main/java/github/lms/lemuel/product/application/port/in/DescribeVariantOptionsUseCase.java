package github.lms.lemuel.product.application.port.in;

import java.util.List;

/**
 * SKU 하나가 어떤 옵션 조합인지를 <b>스냅샷 가능한 형태</b>로 풀어 준다.
 *
 * <p>주문이 라인에 옵션을 적어 둘 때 쓴다. 주문 쪽은 카탈로그 테이블 구조(축 행·값 행·매핑 행)를
 * 알 필요가 없고, 축·값의 코드와 이름만 받아 그대로 적는다.
 */
public interface DescribeVariantOptionsUseCase {

    /**
     * @return 차수 오름차순. 옵션 없는 SKU 이거나 해석 불가면 빈 목록(예외를 던지지 않는다 —
     *         주문 생성이 옵션 <b>설명</b> 때문에 실패하면 안 된다. 재고·금액은 이미 SKU 로 확정돼 있다).
     */
    List<OptionDescriptor> describe(Long variantId);

    record OptionDescriptor(int sortOrder, String axisCode, String axisName,
                            String valueCode, String valueName) {
    }
}
