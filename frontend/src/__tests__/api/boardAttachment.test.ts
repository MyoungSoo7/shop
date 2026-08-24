import { describe, it, expect, vi, beforeEach } from 'vitest';
import { boardAttachmentApi, type BoardAttachment } from '@/api/board';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const mocked = vi.mocked(api);

const 사진: BoardAttachment = {
  id: 9, postId: 7, kind: 'IMAGE', originalName: '벚꽃.png', contentType: 'image/png',
  sizeBytes: 2048, sortOrder: 0,
  downloadUrl: '/api/boards/gallery/attachments/9/download',
  createdAt: '2026-08-15T09:10:00',
};

describe('boardAttachmentApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('목록은 글 경로 아래에서 읽는다 — 첨부는 글의 일부다', async () => {
    mocked.get.mockResolvedValue({ data: [사진] });

    await expect(boardAttachmentApi.list('gallery', 7)).resolves.toEqual([사진]);
    expect(mocked.get).toHaveBeenCalledWith('/api/boards/gallery/posts/7/attachments');
  });

  it('업로드는 FormData 로 보내고 Content-Type 을 직접 지정하지 않는다', async () => {
    mocked.post.mockResolvedValue({ data: 사진 });
    const file = new File(['bytes'], '벚꽃.png', { type: 'image/png' });

    await expect(boardAttachmentApi.upload('gallery', 7, file)).resolves.toEqual(사진);

    const [url, body, config] = mocked.post.mock.calls[0];
    expect(url).toBe('/api/boards/gallery/posts/7/attachments');
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBe(file);
    // boundary 는 브라우저가 붙인다 — 손으로 헤더를 지정하면 boundary 가 빠져 파싱이 깨진다.
    // 어차피 서버는 이 헤더를 믿지 않고 매직바이트로 형식을 다시 판정한다.
    expect(config).toBeUndefined();
  });

  it('삭제는 게시판 경로 + 첨부 식별자로 보낸다 — 글 식별자는 서버가 첨부에서 되찾는다', async () => {
    mocked.delete.mockResolvedValue({ data: undefined });

    await boardAttachmentApi.remove('gallery', 9);

    expect(mocked.delete).toHaveBeenCalledWith('/api/boards/gallery/attachments/9');
  });
});
