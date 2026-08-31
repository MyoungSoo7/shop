package github.lms.lemuel.seller.domain;

import java.math.BigDecimal;

/**
 * 신청서가 담고 있는 <b>상품 그 자체</b> — 이름·설명·가격·재고·분류·이미지·노출여부.
 *
 * <p>신청서의 상태(대기·승인·반려)와 내용을 갈라 둔 이유는, 이 부분만 셀러가 고칠 수 있고
 * 상태는 전이로만 바뀌기 때문이다. 한 덩어리로 두면 수정 메서드가 상태 필드까지 받게 되고,
 * 그러면 "수정" 이 상태를 되돌릴 수 있는 문이 된다.
 *
 * <h2>검증을 도메인에 두는 이유</h2>
 * 같은 규칙이 웹(@Valid)·이벤트 발행·심사 승인 세 경로에서 필요하다. 컨트롤러 애너테이션에만
 * 두면 나머지 둘이 규칙 밖에 놓인다 — 특히 승인 경로가 그렇다. 승인은 이미 저장된 내용을
 * 카탈로그로 내보내는 일이라 입력 검증을 다시 거치지 않는데, 그 사이 데이터가 손으로 고쳐졌을
 * 수 있다. 규칙이 값 옆에 있으면 어느 경로로 들어와도 같은 답이 나온다.
 *
 * <p><b>가격은 {@link BigDecimal} 이다.</b> 돈에 {@code double} 을 쓰지 않는다(CLAUDE.md).
 * 여기서 double 을 쓰면 12,900원짜리가 카탈로그에 12,899.999... 로 실린다.
 *
 * @param name 상품명. 필수 — 이름 없는 상품은 카탈로그에 실릴 수 없다.
 * @param description 상세 설명. 없어도 된다.
 * @param price 판매가. 0 이상. 0원 상품을 막지 않는 이유는 사은품·체험판이 실제로 있어서다.
 * @param stock 재고. 0 이상. 0 으로 등록하고 나중에 채우는 것이 정상 흐름이다.
 * @param category 분류. 없어도 된다 — 운영자가 심사에서 채우는 경우가 있다.
 * @param imageUrl 대표 이미지. 없어도 된다.
 * @param displayVisible 노출 여부(레퍼런스의 {@code PRODUCTVIEWYN}). 승인돼도 꺼 둘 수 있다.
 */
public record ProductContent(
        String name,
        String description,
        BigDecimal price,
        int stock,
        String category,
        String imageUrl,
        boolean displayVisible) {

    /** 상품명 상한 — V1 마이그레이션의 {@code VARCHAR(300)} 과 짝이다. */
    public static final int MAX_NAME_LENGTH = 300;

    public ProductContent {
        name = trimToNull(name);
        if (name == null) {
            throw new IllegalArgumentException("상품명은 필수입니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            // 자르지 않고 거절한다. 조용히 자르면 셀러는 자기가 입력한 이름으로 팔리고 있다고
            // 믿는데 실제 카탈로그에는 뒷부분이 없는 다른 이름이 실린다.
            throw new IllegalArgumentException(
                    "상품명은 " + MAX_NAME_LENGTH + "자 이하여야 합니다 (입력 " + name.length() + "자).");
        }
        if (price == null) {
            throw new IllegalArgumentException("판매가는 필수입니다.");
        }
        if (price.signum() < 0) {
            throw new IllegalArgumentException("판매가는 0원 이상이어야 합니다: " + price.toPlainString());
        }
        if (stock < 0) {
            throw new IllegalArgumentException("재고는 0개 이상이어야 합니다: " + stock);
        }
        description = trimToNull(description);
        category = trimToNull(category);
        imageUrl = trimToNull(imageUrl);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
