package github.lms.lemuel.product.domain;

/**
 * 옵션 축의 입력 표현 방식.
 *
 * <p>표현 방식은 축의 성질이지 값의 성질이 아니다 — 색상 축은 SWATCH, 사이즈 축은 SELECT 처럼
 * 축 단위로 정해지고 그 축의 모든 값이 같은 방식으로 그려진다.
 */
public enum OptionInputType {

    /** 드롭다운/라디오 — 기본. */
    SELECT,

    /** 색상 칩 — 값마다 표시색(swatchHex)이 필요하다. */
    SWATCH,

    /** 각인 문구처럼 구매자가 직접 입력하는 축. 표준값 목록을 갖지 않는다. */
    TEXT;

    /** 이 축의 값이 표시색을 반드시 가져야 하는가. */
    public boolean requiresSwatch() {
        return this == SWATCH;
    }

    /** 이 축이 표준값 목록(option_axis_values)으로 선택지를 구성하는가. */
    public boolean hasEnumeratedValues() {
        return this != TEXT;
    }
}
