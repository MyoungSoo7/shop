import api from './axios';

/**
 * 표준 옵션 축·값 카탈로그 API (ADMIN).
 *
 * <p>축은 상품 간 <b>재사용</b>된다 — "사이즈" 가 판매자마다 제각각이면 파셋 검색·통계가 성립하지
 * 않기 때문이다. 상품은 이 축을 채택할 뿐이고, 채택 사실과 상품이 실제로 파는 값은 별도 테이블이
 * 들고 있다.
 *
 * <p><b>코드는 불변이다.</b> SKU 조합 서명이 축·값 id 로 묶여 있고 코드는 그 id 를 사람이 부르는
 * 이름표다. 그래서 수정 API 는 이름·표시색·정렬만 받는다.
 */

export type OptionInputType = 'SELECT' | 'SWATCH' | 'TEXT';

export interface OptionAxis {
  id: number;
  code: string;
  name: string;
  inputType: OptionInputType;
  active: boolean;
}

export interface OptionAxisValue {
  id: number;
  axisId: number;
  code: string;
  name: string;
  /** SWATCH 축에서만 채워진다. 비어 있는데 축이 SWATCH 면 화면이 빈 칩을 그린다 */
  swatchHex: string | null;
  sortOrder: number;
  active: boolean;
}

export interface CreateAxisPayload {
  code: string;
  name: string;
  inputType: OptionInputType;
}

export interface UpdateAxisPayload {
  name: string;
  inputType: OptionInputType;
}

export interface AddValuePayload {
  code: string;
  name: string;
  swatchHex: string | null;
  sortOrder: number;
}

export interface UpdateValuePayload {
  name: string;
  swatchHex: string | null;
  sortOrder: number;
}

const axisPath = (code: string, suffix = '') =>
  `/admin/option-catalog/axes/${encodeURIComponent(code)}${suffix}`;

const valuePath = (axisCode: string, valueCode: string, suffix = '') =>
  `${axisPath(axisCode)}/values/${encodeURIComponent(valueCode)}${suffix}`;

export const optionCatalogApi = {
  listAxes: async (): Promise<OptionAxis[]> =>
    (await api.get<OptionAxis[]>('/admin/option-catalog/axes')).data,

  createAxis: async (payload: CreateAxisPayload): Promise<OptionAxis> =>
    (await api.post<OptionAxis>('/admin/option-catalog/axes', payload)).data,

  updateAxis: async (code: string, payload: UpdateAxisPayload): Promise<OptionAxis> =>
    (await api.patch<OptionAxis>(axisPath(code), payload)).data,

  setAxisActive: async (code: string, active: boolean): Promise<OptionAxis> =>
    (await api.patch<OptionAxis>(axisPath(code, '/active'), null, { params: { active } })).data,

  /** 비활성 값도 함께 온다 — 운영 화면이 되살릴 수 있어야 한다 */
  listValues: async (axisCode: string): Promise<OptionAxisValue[]> =>
    (await api.get<OptionAxisValue[]>(axisPath(axisCode, '/values'))).data,

  addValue: async (axisCode: string, payload: AddValuePayload): Promise<OptionAxisValue> =>
    (await api.post<OptionAxisValue>(axisPath(axisCode, '/values'), payload)).data,

  updateValue: async (axisCode: string, valueCode: string,
                      payload: UpdateValuePayload): Promise<OptionAxisValue> =>
    (await api.patch<OptionAxisValue>(valuePath(axisCode, valueCode), payload)).data,

  setValueActive: async (axisCode: string, valueCode: string,
                         active: boolean): Promise<OptionAxisValue> =>
    (await api.patch<OptionAxisValue>(valuePath(axisCode, valueCode, '/active'), null,
      { params: { active } })).data,
};
