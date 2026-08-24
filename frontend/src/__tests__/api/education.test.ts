import { describe, it, expect, vi, beforeEach } from 'vitest';
import { educationApi } from '@/api/education';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 교육 과정 관리 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 것은 <b>게시 상태 전이가 저마다 다른 경로</b>라는 점이다. 상태를 본문
 * 필드로 넘기는 형태였다면 화면이 임의의 문자열을 밀어 넣을 수 있고, 서버의 상태머신이
 * 화면 실수까지 떠안게 된다.
 */
describe('educationApi — 과정', () => {
  it('목록은 필터를 params 로 넘긴다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await educationApi.list({ status: 'PUBLISHED', query: '스프링', page: 0, size: 20 });

    expect(mocked.get).toHaveBeenCalledWith('/admin/education/courses', {
      params: { status: 'PUBLISHED', query: '스프링', page: 0, size: 20 },
    });
  });

  it('인자를 생략해도 빈 params 로 부른다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await educationApi.list();

    expect(mocked.get).toHaveBeenCalledWith('/admin/education/courses', { params: {} });
  });

  it('생성과 수정은 메서드로 갈린다 — 수정은 id 를 경로에 싣는다', async () => {
    mocked.post.mockResolvedValue({ data: { id: 'c1' } } as never);
    mocked.put.mockResolvedValue({ data: { id: 'c1' } } as never);

    await educationApi.create({ title: '헥사고날 입문' });
    expect(mocked.post).toHaveBeenCalledWith('/admin/education/courses', { title: '헥사고날 입문' });

    await educationApi.update('c1', { title: '헥사고날 심화' });
    expect(mocked.put).toHaveBeenCalledWith('/admin/education/courses/c1', { title: '헥사고날 심화' });
  });

  it('게시·숨김·종료는 각자의 경로다 — 상태 문자열을 본문으로 받지 않는다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await educationApi.publish('c1');
    await educationApi.hide('c1');
    await educationApi.close('c1');

    expect(mocked.post.mock.calls.map(([p]) => p)).toEqual([
      '/admin/education/courses/c1/publish',
      '/admin/education/courses/c1/hide',
      '/admin/education/courses/c1/close',
    ]);
    expect(mocked.post.mock.calls.every(([, body]) => body === undefined)).toBe(true);
  });
});

describe('educationApi — 차시', () => {
  it('조회·추가는 과정 아래 경로를 쓴다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);
    mocked.post.mockResolvedValue({ data: {} } as never);

    await educationApi.lessons('c1');
    expect(mocked.get).toHaveBeenCalledWith('/admin/education/courses/c1/lessons');

    const body = {
      title: '1차시', sequence: 1, contentType: 'VIDEO' as const,
      contentRef: 'https://v/1', required: true,
    };
    await educationApi.addLesson('c1', body);
    expect(mocked.post).toHaveBeenCalledWith('/admin/education/courses/c1/lessons', body);
  });

  it('순서 변경은 id 배열을 통째로 보낸다 — 한 건씩 옮기면 중간 상태에서 순서가 겹친다', async () => {
    mocked.post.mockResolvedValue({ data: [] } as never);

    await educationApi.reorderLessons('c1', ['l3', 'l1', 'l2']);

    expect(mocked.post).toHaveBeenCalledWith('/admin/education/courses/c1/lessons/reorder', {
      lessonIds: ['l3', 'l1', 'l2'],
    });
  });
});
