import api from './axios';

/**
 * 구매자용 옵션 트리 — order-service {@code ProductOptionController} (GET /products/{id}/options).
 *
 * <p><b>왜 생겼나.</b> 옵션을 정의하는 길(관리자 카탈로그)과 옵션을 SKU 로 바꾸는 길
 * ({@code POST /products/{id}/variants/resolve})은 있었지만, <b>구매자에게 고를 것을 보여 주는</b>
 * 읽기 경로가 없었다. SKU 목록({@code GET /products/*&#47;variants})은 재고 수량이 실려 나가는
 * ADMIN 전용이라 구매 화면이 쓸 수 없다. 그래서 상품 상세·주문 화면은 옵션을 아예 그리지 못했고,
 * 이 몰의 모든 주문이 옵션 없는 주문으로 나갔다.
 *
 * <p><b>여기에 variantId 는 없다.</b> 조합마다 SKU id 가 실려 오면 화면이 그 표로 "선택 → SKU" 를
 * 직접 계산하게 되고, 해석 규칙이 서버와 화면 두 곳에 생긴다. 옵션 조합의 주인은
 * {@code resolve} 하나다 — 이 응답은 <b>무엇을 고를 수 있고, 그 조합이 품절인지, 얼마가 더 붙는지</b>
 * 만 알려준다.
 */

export type OptionInputType = 'SELECT' | 'SWATCH' | 'TEXT';

export interface ProductOptionValue {
  code: string;
  name: string;
  /** SWATCH 축에서만 채워진다(#RRGGBB). */
  swatchHex: string | null;
  sortOrder: number;
}

export interface ProductOptionAxis {
  /** 차수. 0 이 1차 옵션이고 상한이 없다. */
  sortOrder: number;
  code: string;
  name: string;
  inputType: OptionInputType;
  required: boolean;
  values: ProductOptionValue[];
  /**
   * TEXT 축에 적을 수 있는 최대 글자 수. 선택형 축에서는 null 이다.
   *
   * <p>maxlength 를 걸어 주기 위한 안내값일 뿐이다 — 요청을 직접 만들면 이 속성은
   * 그냥 없는 것이므로, 길이·필수 여부는 주문 시점에 서버가 다시 검사한다.
   */
  textMaxLength: number | null;
}

/** 실제로 존재하는 SKU 하나에 대응하는 조합. 재고 수량 대신 `available` 한 비트만 온다. */
export interface ProductOptionCombination {
  selections: { axisCode: string; valueCode: string }[];
  available: boolean;
  additionalPrice: number | null;
}

export interface ProductOptions {
  productId: number;
  axes: ProductOptionAxis[];
  combinations: ProductOptionCombination[];
}

export const productOptionApi = {
  /** 옵션이 없는 상품이면 `axes: []` 가 온다 — 없는 상품(404)과 다른 상태다. */
  describe: async (productId: number): Promise<ProductOptions> =>
    (await api.get<ProductOptions>(`/products/${productId}/options`)).data,
};

/**
 * 선택(축코드 → 값코드)이 어느 조합과 맞는지 찾는다.
 *
 * <p>조합 판정을 화면이 하는 것과 <b>SKU 해석</b>을 화면이 하는 것은 다르다. 앞은 "이 칸을 회색으로
 * 칠할까"라는 표시 문제라 틀려도 서버가 막아 주고, 뒤는 무엇을 파는가라는 사실 문제라 두 곳에
 * 두면 안 된다. 그래서 여기서는 조합의 <b>존재/품절</b>만 보고, 실제 주문은 resolve 를 다시 부른다.
 */
export function matchCombination(
  combinations: ProductOptionCombination[],
  selection: Record<string, string>
): ProductOptionCombination | undefined {
  const chosen = Object.entries(selection).filter(([, v]) => v);
  if (chosen.length === 0) return undefined;
  return combinations.find(
    (c) =>
      c.selections.length === chosen.length &&
      chosen.every(([axisCode, valueCode]) =>
        c.selections.some((s) => s.axisCode === axisCode && s.valueCode === valueCode)
      )
  );
}

/**
 * 아직 고르지 않은 축의 값 중, 지금까지의 선택과 함께 <b>살 수 있는 조합이 하나도 없는</b> 값을 모은다.
 * 품절 조합을 미리 회색으로 죽여 두면 "골랐는데 주문 단계에서 거절" 을 줄인다.
 */
export function unavailableValueCodes(
  combinations: ProductOptionCombination[],
  axes: ProductOptionAxis[],
  selection: Record<string, string>
): Record<string, Set<string>> {
  const result: Record<string, Set<string>> = {};
  for (const axis of axes) {
    const others = Object.entries(selection).filter(([code, v]) => v && code !== axis.code);
    const dead = new Set<string>();
    for (const value of axis.values) {
      const anyAvailable = combinations.some(
        (c) =>
          c.available &&
          c.selections.some((s) => s.axisCode === axis.code && s.valueCode === value.code) &&
          others.every(([axisCode, valueCode]) =>
            c.selections.some((s) => s.axisCode === axisCode && s.valueCode === valueCode)
          )
      );
      if (!anyAvailable) dead.add(value.code);
    }
    result[axis.code] = dead;
  }
  return result;
}
