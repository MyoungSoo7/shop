import axios from 'axios';

/**
 * 셸 네비게이션 메뉴 API — `GET /api/menus/me`.
 *
 * 관리 CRUD(`/admin/menus`, `@/api/system` 의 `menuApi`)와 분리돼 있다. 이쪽 응답은
 * 서버가 호출자 역할·권한으로 이미 걸러 내려보낸 것이라, 클라이언트가 다시 필터링하지 않는다.
 *
 * <p><b>전역 인터셉터를 쓰지 않는 이유</b>: 공용 `@/api/axios` 인스턴스는 401 을 받으면 토큰을
 * 지우고 로그인으로 보낸다. 메뉴 조회는 셸이 모든 화면 진입에서 부르는 부가 요청이라, 이 한 번의
 * 실패가 사용자를 로그아웃시켜서는 안 된다(메뉴가 안 보이는 것과 세션이 끊기는 것은 전혀 다른
 * 사고다). 실패는 호출부가 폴백 스냅샷으로 조용히 흡수한다.
 */

export type MenuArea = 'SHOP' | 'SELLER' | 'BACKOFFICE' | 'CORP' | 'CEO' | 'SYSTEM';

export type MenuNodeType = 'GROUP' | 'ITEM' | 'DIVIDER';

export interface NavMenuNode {
  id: number;
  /** 사이드바 머리글에 쓰는 이름 */
  name: string;
  /** 상단 네비에 쓰는 라벨 (서버가 shortName 우선으로 계산해 준다) */
  label: string;
  /** 착지 경로. DIVIDER 이면 null */
  path: string | null;
  icon: string | null;
  description: string | null;
  area: MenuArea | null;
  type: MenuNodeType;
  children: NavMenuNode[];
}

const menuClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json;charset=UTF-8' },
});

// 토큰은 붙인다 — 서버가 역할별로 트리를 걸러 주려면 필요하다. 없으면 공개 메뉴만 돌아온다.
menuClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export const navMenuApi = {
  /** GET /api/menus/me — 내 권한으로 걸러진 메뉴 트리 */
  getMine: async (): Promise<NavMenuNode[]> =>
    (await menuClient.get<NavMenuNode[]>('/api/menus/me')).data,
};
