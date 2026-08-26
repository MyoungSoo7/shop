import api from './axios';

/**
 * 사이트 팝업 관리 API — dentis 의 admin/site/popup 묶음.
 *
 * <p>노출 여부({@code visible}/{@code scheduled}/{@code expired})를 서버가 계산해 준다. 화면이
 * 시작·종료 시각으로 직접 판단하면 브라우저 시계로 판정하게 되고, 서버가 안 띄우는 팝업을
 * 관리 화면만 "노출 중"으로 표시하는 어긋남이 생긴다.
 */
export interface Popup {
  id: string;
  title: string;
  imageUrl: string | null;
  linkUrl: string | null;
  openInNewWindow: boolean;
  startsAt: string;
  endsAt: string;
  sortOrder: number;
  active: boolean;
  deleted: boolean;
  deletedAt: string | null;
  visible: boolean;
  scheduled: boolean;
  expired: boolean;
  updatedBy: string;
  version: number;
}

export interface PopupSaveBody {
  title: string;
  imageUrl?: string;
  linkUrl?: string;
  openInNewWindow: boolean;
  startsAt: string;
  endsAt: string;
  sortOrder: number;
}

const base = '/api/ops/popups';

export const popupApi = {
  list: async () => (await api.get<Popup[]>(base)).data,
  visible: async () => (await api.get<Popup[]>(`${base}/visible`)).data,
  get: async (id: string) => (await api.get<Popup>(`${base}/${id}`)).data,
  register: async (body: PopupSaveBody) => (await api.post<Popup>(base, body)).data,
  update: async (id: string, body: PopupSaveBody) => (await api.put<Popup>(`${base}/${id}`, body)).data,
  changeActivation: async (id: string, active: boolean) =>
    (await api.put<Popup>(`${base}/${id}/activation`, { active })).data,
  /** 치운다. 204 가 아니라 바뀐 팝업이 돌아온다. */
  remove: async (id: string) => (await api.delete<Popup>(`${base}/${id}`)).data,
};
