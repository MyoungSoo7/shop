import api from './axios';

// ════════════════════════════════════════════════════════════════════════════
// 공통코드 (Common Code)
// ════════════════════════════════════════════════════════════════════════════
export interface CommonCodeGroup {
  groupCode: string;
  name: string;
  description?: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CommonCode {
  id: number;
  groupCode: string;
  code: string;
  label: string;
  sortOrder: number;
  active: boolean;
  extra1?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CommonCodeGroupCreateRequest {
  groupCode: string;
  name: string;
  description?: string;
}
export interface CommonCodeGroupUpdateRequest {
  name: string;
  description?: string;
  active: boolean;
}
export interface CommonCodeCreateRequest {
  groupCode: string;
  code: string;
  label: string;
  sortOrder: number;
  extra1?: string;
}
export interface CommonCodeUpdateRequest {
  label: string;
  sortOrder: number;
  active: boolean;
  extra1?: string;
}

export const commonCodeApi = {
  /** GET /admin/common-codes/groups */
  getGroups: async (): Promise<CommonCodeGroup[]> =>
    (await api.get<CommonCodeGroup[]>('/admin/common-codes/groups')).data,

  /** POST /admin/common-codes/groups */
  createGroup: async (body: CommonCodeGroupCreateRequest): Promise<CommonCodeGroup> =>
    (await api.post<CommonCodeGroup>('/admin/common-codes/groups', body)).data,

  /** PUT /admin/common-codes/groups/{groupCode} */
  updateGroup: async (groupCode: string, body: CommonCodeGroupUpdateRequest): Promise<CommonCodeGroup> =>
    (await api.put<CommonCodeGroup>(`/admin/common-codes/groups/${encodeURIComponent(groupCode)}`, body)).data,

  /** DELETE /admin/common-codes/groups/{groupCode} */
  deleteGroup: async (groupCode: string): Promise<void> => {
    await api.delete(`/admin/common-codes/groups/${encodeURIComponent(groupCode)}`);
  },

  /** GET /admin/common-codes/groups/{groupCode}/codes */
  getCodes: async (groupCode: string): Promise<CommonCode[]> =>
    (await api.get<CommonCode[]>(`/admin/common-codes/groups/${encodeURIComponent(groupCode)}/codes`)).data,

  /** POST /admin/common-codes */
  createCode: async (body: CommonCodeCreateRequest): Promise<CommonCode> =>
    (await api.post<CommonCode>('/admin/common-codes', body)).data,

  /** PUT /admin/common-codes/{id} */
  updateCode: async (id: number, body: CommonCodeUpdateRequest): Promise<CommonCode> =>
    (await api.put<CommonCode>(`/admin/common-codes/${id}`, body)).data,

  /** DELETE /admin/common-codes/{id} */
  deleteCode: async (id: number): Promise<void> => {
    await api.delete(`/admin/common-codes/${id}`);
  },
};

// ════════════════════════════════════════════════════════════════════════════
// 메뉴 (Menu)
// ════════════════════════════════════════════════════════════════════════════
/** 메뉴 영역 — 하나의 셸(상단 네비 + 사이드바)이 담당하는 페르소나 묶음. 서버 enum 과 1:1. */
export type MenuArea = 'SHOP' | 'SELLER' | 'BACKOFFICE' | 'CORP' | 'CEO' | 'SYSTEM';

/** 노드 종류 — GROUP 은 하위를 거느리는 묶음, DIVIDER 는 경로가 없는 구분선. */
export type MenuNodeType = 'GROUP' | 'ITEM' | 'DIVIDER';

export const MENU_AREAS: MenuArea[] = ['SHOP', 'SELLER', 'BACKOFFICE', 'CORP', 'CEO', 'SYSTEM'];
export const MENU_TYPES: MenuNodeType[] = ['GROUP', 'ITEM', 'DIVIDER'];

export interface MenuNode {
  id: number;
  parentId: number | null;
  name: string;
  /** 상단 네비용 짧은 이름 (없으면 name 사용) */
  shortName?: string | null;
  path?: string | null;
  icon?: string | null;
  /** 부제 — 사이드바 항목 아래 한 줄 설명 */
  description?: string | null;
  area?: MenuArea | null;
  menuType: MenuNodeType;
  sortOrder: number;
  /** 접근 허용 역할 CSV allowlist (예: 'ADMIN,MANAGER'). 비면 제한 없음 */
  requiredRole?: string | null;
  /** 접근 필요 권한 코드 (permissions.code) */
  requiredPermission?: string | null;
  visible: boolean;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  children: MenuNode[];
}

export interface MenuCreateRequest {
  name: string;
  shortName?: string;
  path?: string;
  icon?: string;
  description?: string;
  area: MenuArea;
  menuType: MenuNodeType;
  parentId?: number | null;
  sortOrder: number;
  requiredRole?: string;
  requiredPermission?: string;
  visible: boolean;
}
export interface MenuUpdateRequest extends MenuCreateRequest {
  active: boolean;
}

export interface MenuReorderItem {
  id: number;
  parentId: number | null;
  sortOrder: number;
}

export const menuApi = {
  /** GET /admin/menus (트리) */
  getTree: async (): Promise<MenuNode[]> =>
    (await api.get<MenuNode[]>('/admin/menus')).data,

  /** GET /admin/menus/flat */
  getFlat: async (): Promise<MenuNode[]> =>
    (await api.get<MenuNode[]>('/admin/menus/flat')).data,

  /** POST /admin/menus */
  create: async (body: MenuCreateRequest): Promise<MenuNode> =>
    (await api.post<MenuNode>('/admin/menus', body)).data,

  /** PUT /admin/menus/{id} */
  update: async (id: number, body: MenuUpdateRequest): Promise<MenuNode> =>
    (await api.put<MenuNode>(`/admin/menus/${id}`, body)).data,

  /** PATCH /admin/menus/reorder — 부모/정렬 배치 저장 (순환 참조 시 400) */
  reorder: async (items: MenuReorderItem[]): Promise<MenuNode[]> =>
    (await api.patch<MenuNode[]>('/admin/menus/reorder', { items })).data,

  /** DELETE /admin/menus/{id} */
  remove: async (id: number): Promise<void> => {
    await api.delete(`/admin/menus/${id}`);
  },
};

// ════════════════════════════════════════════════════════════════════════════
// RBAC (Role / Permission)
// ════════════════════════════════════════════════════════════════════════════
export interface Role {
  id: number;
  code: string;
  name: string;
  description?: string | null;
  builtin: boolean;
  createdAt: string;
  permissionIds: number[];
  permissionCodes: string[];
}

export interface Permission {
  id: number;
  code: string;
  name: string;
  category: string;
  description?: string | null;
}

export interface RoleCreateRequest {
  code: string;
  name: string;
  description?: string;
}
export interface RoleUpdateRequest {
  name: string;
  description?: string;
}

export const rbacApi = {
  /** GET /admin/rbac/roles */
  getRoles: async (): Promise<Role[]> =>
    (await api.get<Role[]>('/admin/rbac/roles')).data,

  /** GET /admin/rbac/permissions */
  getPermissions: async (): Promise<Permission[]> =>
    (await api.get<Permission[]>('/admin/rbac/permissions')).data,

  /** GET /admin/rbac/roles/{id} */
  getRole: async (id: number): Promise<Role> =>
    (await api.get<Role>(`/admin/rbac/roles/${id}`)).data,

  /** PUT /admin/rbac/roles/{id}/permissions */
  updateRolePermissions: async (id: number, permissionIds: number[]): Promise<Role> =>
    (await api.put<Role>(`/admin/rbac/roles/${id}/permissions`, { permissionIds })).data,

  /** POST /admin/rbac/roles — 커스텀 역할 생성 */
  createRole: async (body: RoleCreateRequest): Promise<Role> =>
    (await api.post<Role>('/admin/rbac/roles', body)).data,

  /** PUT /admin/rbac/roles/{id} — 이름/설명 수정 (코드 불변) */
  updateRole: async (id: number, body: RoleUpdateRequest): Promise<Role> =>
    (await api.put<Role>(`/admin/rbac/roles/${id}`, body)).data,

  /** DELETE /admin/rbac/roles/{id} — builtin 역할은 400 */
  deleteRole: async (id: number): Promise<void> => {
    await api.delete(`/admin/rbac/roles/${id}`);
  },

  /** POST /admin/rbac/roles/{id}/clone — 권한 매핑까지 복제 */
  cloneRole: async (id: number, code: string, name?: string): Promise<Role> =>
    (await api.post<Role>(`/admin/rbac/roles/${id}/clone`, { code, name })).data,
};
