import { describe, it, expect, vi, beforeEach } from 'vitest';
import { commonCodeApi, menuApi, rbacApi } from '@/api/system';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

const mocked = vi.mocked(api);

beforeEach(() => {
  vi.clearAllMocks();
  mocked.get.mockResolvedValue({ data: [] } as never);
  mocked.post.mockResolvedValue({ data: {} } as never);
  mocked.put.mockResolvedValue({ data: {} } as never);
  mocked.patch.mockResolvedValue({ data: [] } as never);
  mocked.delete.mockResolvedValue({ data: undefined } as never);
});

/**
 * 시스템 관리 API 클라이언트 계약 — <b>경로와 페이로드 모양</b>을 고정한다.
 *
 * <p>이 모듈은 얇은 래퍼라 로직이 없어 보이지만, 깨지면 화면 전체가 조용히 404 를 받는다.
 * 특히 경로에 사용자 입력(groupCode)이 들어가는 자리는 인코딩 여부가 계약이다 —
 * `SALES/TYPE` 같은 코드가 들어오면 인코딩 없이는 경로가 갈라진다.
 */
describe('commonCodeApi — 공통코드', () => {
  it('그룹 목록·생성·수정·삭제가 규약 경로를 친다', async () => {
    await commonCodeApi.getGroups();
    expect(mocked.get).toHaveBeenCalledWith('/admin/common-codes/groups');

    await commonCodeApi.createGroup({ groupCode: 'ORDER_STATUS', name: '주문상태' });
    expect(mocked.post).toHaveBeenCalledWith('/admin/common-codes/groups',
      { groupCode: 'ORDER_STATUS', name: '주문상태' });

    await commonCodeApi.updateGroup('ORDER_STATUS', { name: '주문 상태', active: true });
    expect(mocked.put).toHaveBeenCalledWith('/admin/common-codes/groups/ORDER_STATUS',
      { name: '주문 상태', active: true });

    await commonCodeApi.deleteGroup('ORDER_STATUS');
    expect(mocked.delete).toHaveBeenCalledWith('/admin/common-codes/groups/ORDER_STATUS');
  });

  it('그룹 코드는 경로에 넣기 전에 인코딩된다 — 슬래시가 경로를 가르지 않는다', async () => {
    await commonCodeApi.getCodes('SALES/TYPE');

    expect(mocked.get).toHaveBeenCalledWith('/admin/common-codes/groups/SALES%2FTYPE/codes');
  });

  it('코드 생성·수정·삭제가 규약 경로를 친다', async () => {
    await commonCodeApi.createCode({ groupCode: 'G', code: 'C', label: '라벨', sortOrder: 1 });
    expect(mocked.post).toHaveBeenCalledWith('/admin/common-codes',
      { groupCode: 'G', code: 'C', label: '라벨', sortOrder: 1 });

    await commonCodeApi.updateCode(7, { label: '수정', sortOrder: 2, active: false });
    expect(mocked.put).toHaveBeenCalledWith('/admin/common-codes/7',
      { label: '수정', sortOrder: 2, active: false });

    await commonCodeApi.deleteCode(7);
    expect(mocked.delete).toHaveBeenCalledWith('/admin/common-codes/7');
  });

  it('응답 본문(data)만 돌려준다 — 화면이 axios 응답 껍데기를 알 필요가 없다', async () => {
    mocked.get.mockResolvedValueOnce({ data: [{ groupCode: 'G' }] } as never);

    await expect(commonCodeApi.getGroups()).resolves.toEqual([{ groupCode: 'G' }]);
  });
});

describe('menuApi — 메뉴 트리', () => {
  it('트리와 평면 목록은 서로 다른 경로다', async () => {
    await menuApi.getTree();
    expect(mocked.get).toHaveBeenCalledWith('/admin/menus');

    await menuApi.getFlat();
    expect(mocked.get).toHaveBeenCalledWith('/admin/menus/flat');
  });

  it('생성·수정·삭제가 규약 경로를 친다', async () => {
    await menuApi.create({ name: '신규', path: '/x', area: 'SYSTEM', type: 'ITEM' } as never);
    expect(mocked.post).toHaveBeenCalledWith('/admin/menus',
      { name: '신규', path: '/x', area: 'SYSTEM', type: 'ITEM' });

    await menuApi.update(3, { name: '수정' } as never);
    expect(mocked.put).toHaveBeenCalledWith('/admin/menus/3', { name: '수정' });

    await menuApi.remove(3);
    expect(mocked.delete).toHaveBeenCalledWith('/admin/menus/3');
  });

  it('재배치는 items 로 감싸 보낸다 — 서버가 배열을 통째로 받지 않는다', async () => {
    const items = [{ id: 1, parentId: null, sortOrder: 0 }, { id: 2, parentId: 1, sortOrder: 1 }];

    await menuApi.reorder(items);

    expect(mocked.patch).toHaveBeenCalledWith('/admin/menus/reorder', { items });
  });
});

describe('rbacApi — 역할·권한', () => {
  it('역할·권한 조회가 규약 경로를 친다', async () => {
    await rbacApi.getRoles();
    expect(mocked.get).toHaveBeenCalledWith('/admin/rbac/roles');

    await rbacApi.getPermissions();
    expect(mocked.get).toHaveBeenCalledWith('/admin/rbac/permissions');

    await rbacApi.getRole(5);
    expect(mocked.get).toHaveBeenCalledWith('/admin/rbac/roles/5');
  });

  it('권한 매핑 저장은 permissionIds 로 감싸 보낸다', async () => {
    await rbacApi.updateRolePermissions(5, [1, 2, 3]);

    expect(mocked.put).toHaveBeenCalledWith('/admin/rbac/roles/5/permissions', { permissionIds: [1, 2, 3] });
  });

  it('역할 생성·수정·삭제·복제가 규약 경로를 친다', async () => {
    await rbacApi.createRole({ code: 'OPS', name: '운영' });
    expect(mocked.post).toHaveBeenCalledWith('/admin/rbac/roles', { code: 'OPS', name: '운영' });

    await rbacApi.updateRole(5, { name: '운영2' });
    expect(mocked.put).toHaveBeenCalledWith('/admin/rbac/roles/5', { name: '운영2' });

    await rbacApi.deleteRole(5);
    expect(mocked.delete).toHaveBeenCalledWith('/admin/rbac/roles/5');

    await rbacApi.cloneRole(5, 'OPS_COPY', '운영 복제');
    expect(mocked.post).toHaveBeenCalledWith('/admin/rbac/roles/5/clone',
      { code: 'OPS_COPY', name: '운영 복제' });
  });

  it('복제 시 이름을 생략하면 name 은 undefined 로 나간다 — 서버가 원본 이름을 쓴다', async () => {
    await rbacApi.cloneRole(5, 'OPS_COPY');

    expect(mocked.post).toHaveBeenCalledWith('/admin/rbac/roles/5/clone',
      { code: 'OPS_COPY', name: undefined });
  });
});
