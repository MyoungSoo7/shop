import api from './axios';

/**
 * 진열 편성(기획전) API.
 *
 * <p>진열은 분류(카테고리)와 다른 축이다 — 분류는 상품의 성질이라 시간이 없지만, 진열은 <b>기간</b>이
 * 있다. 그래서 편성은 별도 애그리거트로 서고, 이 모듈도 카테고리 API 와 섞지 않는다.
 *
 * <p><b>노출 판정은 서버 몫이다.</b> 운영 목록(`/admin/display-sections`)은 끝난 편성까지 전부 주고,
 * 지금 노출 중인 것은 공개 목록(`/display-sections`)이 시각으로 판정해 준다. 화면이 기간을 직접
 * 계산하면 시계가 어긋난 단말에서 끝난 기획전이 "노출중" 으로 보인다.
 */

export type DisplaySectionKind = 'MAIN' | 'EXHIBITION' | 'CATEGORY_BEST';

export interface DisplaySection {
  id: number;
  /** 대문자·숫자·밑줄 2~50자. 서버 도메인이 형식을 강제한다 */
  code: string;
  name: string;
  kind: DisplaySectionKind;
  /** CATEGORY_BEST 만 카테고리를 지목한다 */
  categoryId: number | null;
  /** 비어 있으면 그 방향은 열려 있다(상시 진열) */
  startsAt: string | null;
  endsAt: string | null;
  sortOrder: number;
  /** 운영자가 내린 플래그. 노출 여부는 이 값 <b>그리고</b> 기간까지 봐야 정해진다 */
  active: boolean;
}

export interface DisplaySectionItem {
  productId: number;
  sortOrder: number;
  /** 고정은 정렬보다 우선한다 — "이건 무조건 맨 앞" */
  pinned: boolean;
}

export interface CreateDisplaySectionPayload {
  code: string;
  name: string;
  kind: DisplaySectionKind;
  categoryId: number | null;
  startsAt: string | null;
  endsAt: string | null;
  sortOrder: number;
}

export interface AddDisplaySectionItemPayload {
  productId: number;
  sortOrder: number;
  pinned: boolean;
}

export interface ReschedulePayload {
  startsAt: string | null;
  endsAt: string | null;
}

const sectionPath = (code: string, suffix = '') =>
  `/admin/display-sections/${encodeURIComponent(code)}${suffix}`;

export const displaySectionApi = {
  /** 운영 목록 — 노출이 끝난 편성까지 전부 */
  listAll: async (): Promise<DisplaySection[]> =>
    (await api.get<DisplaySection[]>('/admin/display-sections')).data,

  /** 지금 노출 중인 편성 — 판정 주체가 서버라는 사실을 화면이 빌려 쓰는 자리 */
  listVisible: async (): Promise<DisplaySection[]> =>
    (await api.get<DisplaySection[]>('/display-sections')).data,

  items: async (code: string): Promise<DisplaySectionItem[]> =>
    (await api.get<DisplaySectionItem[]>(sectionPath(code, '/items'))).data,

  create: async (payload: CreateDisplaySectionPayload): Promise<DisplaySection> =>
    (await api.post<DisplaySection>('/admin/display-sections', payload)).data,

  addItem: async (code: string, payload: AddDisplaySectionItemPayload): Promise<void> => {
    await api.post(sectionPath(code, '/items'), payload);
  },

  removeItem: async (code: string, productId: number): Promise<void> => {
    await api.delete(sectionPath(code, `/items/${productId}`));
  },

  /** 기간 재설정 — 끝난 편성을 되살리는 정상 경로이기도 하다 */
  reschedule: async (code: string, payload: ReschedulePayload): Promise<DisplaySection> =>
    (await api.patch<DisplaySection>(sectionPath(code, '/schedule'), payload)).data,

  setActive: async (code: string, active: boolean): Promise<DisplaySection> =>
    (await api.patch<DisplaySection>(sectionPath(code, '/active'), null, { params: { active } })).data,
};
