import { describe, it, expect, vi, beforeEach } from 'vitest';
import { optionCatalogApi, type OptionAxis, type OptionAxisValue } from '@/api/optionCatalog';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
}));

const mocked = vi.mocked(api);

const color: OptionAxis = { id: 1, code: 'COLOR', name: '색상', inputType: 'SWATCH', active: true };
const red: OptionAxisValue = {
  id: 11, axisId: 1, code: 'RED', name: '빨강', swatchHex: '#FF0000', sortOrder: 0, active: true,
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.get.mockResolvedValue({ data: [] });
  mocked.post.mockResolvedValue({ data: color });
  mocked.patch.mockResolvedValue({ data: color });
});

describe('optionCatalogApi', () => {
  it('축 목록·값 목록을 각자의 경로에서 읽는다', async () => {
    mocked.get.mockResolvedValueOnce({ data: [color] });
    expect(await optionCatalogApi.listAxes()).toEqual([color]);
    expect(mocked.get).toHaveBeenCalledWith('/admin/option-catalog/axes');

    mocked.get.mockResolvedValueOnce({ data: [red] });
    expect(await optionCatalogApi.listValues('COLOR')).toEqual([red]);
    expect(mocked.get).toHaveBeenCalledWith('/admin/option-catalog/axes/COLOR/values');
  });

  it('축을 만들고 고친다 — 코드는 URL 조각으로 인코딩한다', async () => {
    await optionCatalogApi.createAxis({ code: 'CAPACITY', name: '용량', inputType: 'SELECT' });
    expect(mocked.post).toHaveBeenCalledWith('/admin/option-catalog/axes',
      { code: 'CAPACITY', name: '용량', inputType: 'SELECT' });

    await optionCatalogApi.updateAxis('A/B', { name: '에이비', inputType: 'SELECT' });
    expect(mocked.patch).toHaveBeenCalledWith('/admin/option-catalog/axes/A%2FB',
      { name: '에이비', inputType: 'SELECT' });

    await optionCatalogApi.setAxisActive('COLOR', false);
    expect(mocked.patch).toHaveBeenCalledWith('/admin/option-catalog/axes/COLOR/active', null,
      { params: { active: false } });
  });

  it('값을 더하고 고치고 내린다', async () => {
    await optionCatalogApi.addValue('COLOR', { code: 'RED', name: '빨강', swatchHex: '#FF0000', sortOrder: 1 });
    expect(mocked.post).toHaveBeenCalledWith('/admin/option-catalog/axes/COLOR/values',
      { code: 'RED', name: '빨강', swatchHex: '#FF0000', sortOrder: 1 });

    await optionCatalogApi.updateValue('COLOR', 'RED', { name: '진빨강', swatchHex: '#CC0000', sortOrder: 2 });
    expect(mocked.patch).toHaveBeenCalledWith('/admin/option-catalog/axes/COLOR/values/RED',
      { name: '진빨강', swatchHex: '#CC0000', sortOrder: 2 });

    await optionCatalogApi.setValueActive('COLOR', 'RED', false);
    expect(mocked.patch).toHaveBeenCalledWith('/admin/option-catalog/axes/COLOR/values/RED/active', null,
      { params: { active: false } });
  });
});
