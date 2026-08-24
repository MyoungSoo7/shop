import { describe, it, expect, vi, beforeEach } from 'vitest';
import { tagApi } from '@/api/tag';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const tag = { id: 1, name: '신상', color: '#ff0000' };

describe('tagApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('태그를 생성한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: tag });

    const result = await tagApi.createTag({ name: '신상' } as never);

    expect(api.post).toHaveBeenCalledWith('/api/tags', { name: '신상' });
    expect(result.id).toBe(1);
  });

  it('태그 단건을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: tag });

    const result = await tagApi.getTag(1);

    expect(api.get).toHaveBeenCalledWith('/api/tags/1');
    expect(result.name).toBe('신상');
  });

  it('전체 태그 목록을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [tag] });

    const result = await tagApi.getAllTags();

    expect(api.get).toHaveBeenCalledWith('/api/tags');
    expect(result).toHaveLength(1);
  });

  it('상품별 태그 목록을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [tag] });

    const result = await tagApi.getTagsByProduct(42);

    expect(api.get).toHaveBeenCalledWith('/api/tags/product/42');
    expect(result[0].id).toBe(1);
  });

  it('태그를 수정한다', async () => {
    vi.mocked(api.put).mockResolvedValueOnce({ data: { ...tag, name: '베스트' } });

    const result = await tagApi.updateTag(1, { name: '베스트' } as never);

    expect(api.put).toHaveBeenCalledWith('/api/tags/1', { name: '베스트' });
    expect(result.name).toBe('베스트');
  });

  it('태그를 삭제한다', async () => {
    vi.mocked(api.delete).mockResolvedValueOnce({ data: undefined });

    await tagApi.deleteTag(1);

    expect(api.delete).toHaveBeenCalledWith('/api/tags/1');
  });

  it('상품에 태그를 추가한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: undefined });

    await tagApi.addTagToProduct(42, 1);

    expect(api.post).toHaveBeenCalledWith('/api/tags/product/42/tag/1');
  });

  it('상품에서 태그를 제거한다', async () => {
    vi.mocked(api.delete).mockResolvedValueOnce({ data: undefined });

    await tagApi.removeTagFromProduct(42, 1);

    expect(api.delete).toHaveBeenCalledWith('/api/tags/product/42/tag/1');
  });

  it('API 오류는 그대로 전파한다', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { status: 404 } });

    await expect(tagApi.getTag(999)).rejects.toMatchObject({ response: { status: 404 } });
  });
});
