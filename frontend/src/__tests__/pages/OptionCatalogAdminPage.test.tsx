import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import OptionCatalogAdminPage from '@/pages/OptionCatalogAdminPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { optionCatalogApi, type OptionAxis, type OptionAxisValue } from '@/api/optionCatalog';

vi.mock('@/api/optionCatalog', () => ({
  optionCatalogApi: {
    listAxes: vi.fn(),
    listValues: vi.fn(),
    createAxis: vi.fn(),
    updateAxis: vi.fn(),
    setAxisActive: vi.fn(),
    addValue: vi.fn(),
    updateValue: vi.fn(),
    setValueActive: vi.fn(),
  },
}));

const mocked = vi.mocked(optionCatalogApi);

const color: OptionAxis = { id: 1, code: 'COLOR', name: '색상', inputType: 'SWATCH', active: true };
const size: OptionAxis = { id: 2, code: 'SIZE', name: '사이즈', inputType: 'SELECT', active: true };
const engraving: OptionAxis = { id: 3, code: 'ENGRAVING', name: '각인', inputType: 'TEXT', active: true };

const red: OptionAxisValue = {
  id: 11, axisId: 1, code: 'RED', name: '빨강', swatchHex: '#FF0000', sortOrder: 0, active: true,
};
/** 표시색 없는 SWATCH 값 — 화면이 빈 칩을 그리게 되는 조합 */
const blue: OptionAxisValue = {
  id: 12, axisId: 1, code: 'BLUE', name: '파랑', swatchHex: null, sortOrder: 1, active: true,
};

const renderPage = () => render(<ToastProvider><OptionCatalogAdminPage /></ToastProvider>);

const openAxis = async (name: string) => {
  const row = (await screen.findByText(name)).closest('tr')!;
  fireEvent.click(within(row).getByRole('button', { name: '값 보기' }));
  await waitFor(() => expect(mocked.listValues).toHaveBeenCalled());
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.listAxes.mockResolvedValue([color, size, engraving]);
  mocked.listValues.mockResolvedValue([red, blue]);
  mocked.createAxis.mockResolvedValue(size);
  mocked.updateAxis.mockResolvedValue(color);
  mocked.setAxisActive.mockResolvedValue({ ...color, active: false });
  mocked.addValue.mockResolvedValue(red);
  mocked.updateValue.mockResolvedValue(red);
  mocked.setValueActive.mockResolvedValue({ ...red, active: false });
});

describe('OptionCatalogAdminPage', () => {
  it('진입하면 표준 축 목록을 보여 준다', async () => {
    renderPage();

    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());
    expect(await screen.findByText('색상')).toBeInTheDocument();
    expect(screen.getByText('사이즈')).toBeInTheDocument();
  });

  it('축을 고르면 그 축의 표준 값을 읽는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());

    await openAxis('색상');

    expect(mocked.listValues).toHaveBeenCalledWith('COLOR');
    const rows = await screen.findAllByTestId('axis-value-row');
    expect(within(rows[0]).getByText('RED')).toBeInTheDocument();
  });

  it('SWATCH 축에 표시색 없는 값이 있으면 경고로 드러낸다 — 그대로 두면 빈 칩이 그려진다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());

    await openAxis('색상');

    // 경고는 문제 있는 값만 지목한다 — 표시색이 있는 RED 까지 싸잡으면 무엇을 고칠지 알 수 없다
    const warning = screen.getByText(/표시색이 없는 값/);
    expect(warning).toHaveTextContent('BLUE');
    expect(warning).not.toHaveTextContent('RED');
  });

  it('TEXT 축은 표준값을 갖지 않는다고 알리고 추가 폼을 내지 않는다', async () => {
    mocked.listValues.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());

    await openAxis('각인');

    expect(await screen.findByText(/표준값 목록을 갖지 않습니다/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '값 추가' })).not.toBeInTheDocument();
  });

  it('값을 더하면 목록을 다시 읽는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());
    await openAxis('사이즈');

    fireEvent.change(await screen.findByLabelText('값 코드'), { target: { value: 'XL' } });
    fireEvent.change(screen.getByLabelText('값 이름'), { target: { value: '특대' } });
    fireEvent.change(screen.getByLabelText('정렬'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: '값 추가' }));

    await waitFor(() => expect(mocked.addValue).toHaveBeenCalledWith('SIZE',
      { code: 'XL', name: '특대', swatchHex: null, sortOrder: 3 }));
    await waitFor(() => expect(mocked.listValues).toHaveBeenCalledTimes(2));
  });

  it('SWATCH 축에 표시색 없이 값을 넣으려 하면 요청하지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());
    await openAxis('색상');

    fireEvent.change(await screen.findByLabelText('값 코드'), { target: { value: 'GREEN' } });
    fireEvent.change(screen.getByLabelText('값 이름'), { target: { value: '초록' } });
    fireEvent.click(screen.getByRole('button', { name: '값 추가' }));

    await waitFor(() => expect(mocked.addValue).not.toHaveBeenCalled());
  });

  it('값을 고칠 때 코드는 손대지 못한다 — SKU 매핑이 코드에 걸려 있다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());
    await openAxis('색상');

    const rows = await screen.findAllByTestId('axis-value-row');
    fireEvent.click(within(rows[0]).getByRole('button', { name: '수정' }));

    expect(screen.getByLabelText('값 코드')).toBeDisabled();
    fireEvent.change(screen.getByLabelText('값 이름'), { target: { value: '진빨강' } });
    fireEvent.click(screen.getByRole('button', { name: '값 저장' }));

    await waitFor(() => expect(mocked.updateValue).toHaveBeenCalledWith('COLOR', 'RED',
      { name: '진빨강', swatchHex: '#FF0000', sortOrder: 0 }));
  });

  it('값을 내린다 — 카탈로그 표시일 뿐 판매를 멈추지 않는다는 사실을 화면이 말한다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());
    await openAxis('색상');

    const rows = await screen.findAllByTestId('axis-value-row');
    fireEvent.click(within(rows[0]).getByRole('button', { name: '내리기' }));

    await waitFor(() => expect(mocked.setValueActive).toHaveBeenCalledWith('COLOR', 'RED', false));
    expect(await screen.findByText(/이미 그 값을 파는 상품의 판매를 멈추지 않습니다/)).toBeInTheDocument();
  });

  it('새 축을 만들면 목록을 다시 읽는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalledTimes(1));

    fireEvent.change(await screen.findByLabelText('축 코드'), { target: { value: 'CAPACITY' } });
    fireEvent.change(screen.getByLabelText('축 이름'), { target: { value: '용량' } });
    fireEvent.click(screen.getByRole('button', { name: '축 만들기' }));

    await waitFor(() => expect(mocked.createAxis).toHaveBeenCalledWith(
      { code: 'CAPACITY', name: '용량', inputType: 'SELECT' }));
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalledTimes(2));
  });

  it('코드나 이름이 비면 축을 만들지 않는다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalled());

    fireEvent.click(await screen.findByRole('button', { name: '축 만들기' }));

    await waitFor(() => expect(mocked.createAxis).not.toHaveBeenCalled());
  });

  it('축을 내린다', async () => {
    renderPage();
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalledTimes(1));

    const row = (await screen.findByText('색상')).closest('tr')!;
    fireEvent.click(within(row).getByRole('button', { name: '내리기' }));

    await waitFor(() => expect(mocked.setAxisActive).toHaveBeenCalledWith('COLOR', false));
    await waitFor(() => expect(mocked.listAxes).toHaveBeenCalledTimes(2));
  });

  it('축 조회 실패는 화면에 남긴다', async () => {
    mocked.listAxes.mockRejectedValue(new Error('boom'));
    renderPage();

    await waitFor(() => expect(screen.getByText(/옵션 축을 불러오지 못했습니다|boom/)).toBeInTheDocument());
  });
});
