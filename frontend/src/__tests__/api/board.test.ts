import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  boardAdminApi,
  boardApi,
  boardPostApi,
  boardCommentApi,
  BOARD_SKINS,
  BOARD_CONTENT_FORMATS,
  BOARD_SKIN_LABEL,
  skinRequiresAttachments,
  skinRequiresComments,
  type BoardDefinition,
  type BoardCreateRequest,
} from '@/api/board';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const notice: BoardDefinition = {
  id: 1,
  boardKey: 'notice',
  name: '공지사항',
  description: '전사 공지',
  skin: 'LIST',
  path: '/boards/notice',
  content: { contentFormat: 'TEXT', commentsEnabled: false, secretEnabled: false, categoryGroupCode: null },
  attachment: { enabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: ['jpg'] },
  access: { readRoles: [], writeRoles: ['ADMIN'], commentRoles: ['ADMIN'], manageRoles: ['ADMIN'], publicRead: true },
  active: true,
  createdAt: '2026-08-15T00:00:00',
  updatedAt: '2026-08-15T00:00:00',
};

const createBody: BoardCreateRequest = {
  boardKey: 'qna',
  name: '문의',
  skin: 'QNA',
  content: { contentFormat: 'TEXT', commentsEnabled: true, secretEnabled: true, categoryGroupCode: null },
  attachment: { enabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: [] },
  access: { readRoles: ['USER'], writeRoles: ['USER'], commentRoles: ['ADMIN'], manageRoles: ['ADMIN'] },
};

beforeEach(() => {
  vi.resetAllMocks();
});

describe('board 메타 상수', () => {
  it('스킨·본문형식 후보는 라벨과 짝이 맞는다 — 라벨 누락은 셀렉트에 빈 항목으로 샌다', () => {
    expect(BOARD_SKINS).toEqual(['LIST', 'GALLERY', 'FAQ', 'QNA']);
    expect(BOARD_CONTENT_FORMATS).toEqual(['TEXT', 'MARKDOWN', 'HTML']);
    BOARD_SKINS.forEach((skin) => expect(BOARD_SKIN_LABEL[skin]).toBeTruthy());
  });

  it('GALLERY 만 첨부를 전제하고, QNA 만 댓글을 전제한다 (서버 도메인 불변식의 사본)', () => {
    expect(BOARD_SKINS.filter(skinRequiresAttachments)).toEqual(['GALLERY']);
    expect(BOARD_SKINS.filter(skinRequiresComments)).toEqual(['QNA']);
  });
});

describe('boardAdminApi', () => {
  it('목록은 비활성 포함 관리 경로로 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [notice] });

    const result = await boardAdminApi.list();

    expect(api.get).toHaveBeenCalledWith('/admin/boards');
    expect(result).toHaveLength(1);
  });

  it('생성은 boardKey 를 본문에 실어 POST 한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...notice, id: 2, boardKey: 'qna' } });

    const result = await boardAdminApi.create(createBody);

    expect(api.post).toHaveBeenCalledWith('/admin/boards', createBody);
    expect(result.id).toBe(2);
  });

  it('수정은 id 경로로 PUT 한다 (키는 불변이라 본문에 없다)', async () => {
    vi.mocked(api.put).mockResolvedValueOnce({ data: notice });

    await boardAdminApi.update(1, {
      name: '공지', skin: 'LIST', content: createBody.content,
      attachment: createBody.attachment, access: createBody.access,
    });

    expect(api.put).toHaveBeenCalledWith('/admin/boards/1', expect.objectContaining({ name: '공지' }));
  });

  it('닫기/열기는 각각 전용 경로를 쓴다 — 수정으로 상태를 바꾸지 않는다', async () => {
    vi.mocked(api.post)
      .mockResolvedValueOnce({ data: { ...notice, active: false } })
      .mockResolvedValueOnce({ data: notice });

    expect((await boardAdminApi.deactivate(1)).active).toBe(false);
    expect((await boardAdminApi.activate(1)).active).toBe(true);
    expect(api.post).toHaveBeenNthCalledWith(1, '/admin/boards/1/deactivate');
    expect(api.post).toHaveBeenNthCalledWith(2, '/admin/boards/1/activate');
  });

  it('삭제는 본문 없이 DELETE 만 보낸다', async () => {
    vi.mocked(api.delete).mockResolvedValueOnce({ data: undefined });

    await expect(boardAdminApi.remove(1)).resolves.toBeUndefined();
    expect(api.delete).toHaveBeenCalledWith('/admin/boards/1');
  });
});

describe('boardApi (사용자 경로)', () => {
  it('활성 + 읽기 권한이 있는 게시판만 오는 공개 목록을 부른다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [notice] });

    await boardApi.listVisible();

    expect(api.get).toHaveBeenCalledWith('/api/boards');
  });

  it('단건 조회는 id 가 아니라 boardKey 로 간다 — 링크가 키로 만들어지기 때문이다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: notice });

    const result = await boardApi.get('notice');

    expect(api.get).toHaveBeenCalledWith('/api/boards/notice');
    expect(result.path).toBe('/boards/notice');
  });
});

describe('boardPostApi', () => {
  it('목록은 페이지·검색을 쿼리 파라미터로 넘긴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { content: [], page: 1, size: 20, totalElements: 0, totalPages: 0 },
    });

    await boardPostApi.list('notice', { page: 1, size: 20, keyword: '환불' });

    expect(api.get).toHaveBeenCalledWith('/api/boards/notice/posts', {
      params: { page: 1, size: 20, keyword: '환불' },
    });
  });

  it('파라미터를 생략하면 빈 객체로 보낸다 (서버 기본값에 맡긴다)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    });

    await boardPostApi.list('notice');

    expect(api.get).toHaveBeenCalledWith('/api/boards/notice/posts', { params: {} });
  });

  it('상세·생성·수정·삭제는 모두 게시판 키 아래 경로를 쓴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { id: 7 } });
    vi.mocked(api.post).mockResolvedValueOnce({ data: { id: 8 } });
    vi.mocked(api.put).mockResolvedValueOnce({ data: { id: 7 } });
    vi.mocked(api.delete).mockResolvedValueOnce({ data: undefined });
    const body = { title: '제목', content: '본문', secret: false };

    await boardPostApi.read('notice', 7);
    await boardPostApi.create('notice', body);
    await boardPostApi.update('notice', 7, body);
    await boardPostApi.remove('notice', 7);

    expect(api.get).toHaveBeenCalledWith('/api/boards/notice/posts/7');
    expect(api.post).toHaveBeenCalledWith('/api/boards/notice/posts', body);
    expect(api.put).toHaveBeenCalledWith('/api/boards/notice/posts/7', body);
    expect(api.delete).toHaveBeenCalledWith('/api/boards/notice/posts/7');
  });

  it('고정·숨김·복구는 각각 전용 경로다 — 수정으로 상태를 바꾸지 않는다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 7 } });

    await boardPostApi.pin('notice', 7, true);
    await boardPostApi.hide('notice', 7);
    await boardPostApi.restore('notice', 7);

    expect(api.post).toHaveBeenNthCalledWith(1, '/api/boards/notice/posts/7/pin?pinned=true');
    expect(api.post).toHaveBeenNthCalledWith(2, '/api/boards/notice/posts/7/hide');
    expect(api.post).toHaveBeenNthCalledWith(3, '/api/boards/notice/posts/7/restore');
  });
});

describe('boardCommentApi', () => {
  it('댓글 목록은 글 아래 경로에서 읽는다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [] });

    await boardCommentApi.list('qna', 7);

    expect(api.get).toHaveBeenCalledWith('/api/boards/qna/posts/7/comments');
  });

  it('일반 댓글은 parentId 를 null 로 명시해 보낸다 (undefined 는 필드가 통째로 빠진다)', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { id: 1 } });

    await boardCommentApi.create('qna', 7, '답변 부탁드립니다');

    expect(api.post).toHaveBeenCalledWith('/api/boards/qna/posts/7/comments', {
      content: '답변 부탁드립니다', parentId: null,
    });
  });

  it('답글은 부모 댓글 id 를 싣는다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { id: 2 } });

    await boardCommentApi.create('qna', 7, '확인했습니다', 1);

    expect(api.post).toHaveBeenCalledWith('/api/boards/qna/posts/7/comments', {
      content: '확인했습니다', parentId: 1,
    });
  });

  it('댓글 삭제는 글이 아니라 게시판 아래 comments 경로로 간다', async () => {
    vi.mocked(api.delete).mockResolvedValueOnce({ data: undefined });

    await boardCommentApi.remove('qna', 5);

    expect(api.delete).toHaveBeenCalledWith('/api/boards/qna/comments/5');
  });
});
