import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import CategoryCountIntegrityPanel from '@/components/category/CategoryCountIntegrityPanel';
import { ToastProvider } from '@/contexts/ToastContext';
import { categoryIntegrityApi, type CategoryCountIntegrity } from '@/api/categoryIntegrity';

vi.mock('@/api/categoryIntegrity', () => ({
  categoryIntegrityApi: { checkCounts: vi.fn(), refreshCounts: vi.fn() },
}));

const mocked = vi.mocked(categoryIntegrityApi);

const healthy: CategoryCountIntegrity = {
  drifted: 0, healthy: true, byKind: {}, samples: [], unreadable: 0,
};

const drifted: CategoryCountIntegrity = {
  drifted: 97,
  healthy: false,
  byKind: { OVERCOUNT: 1, UNDERCOUNT: 1 },
  samples: [
    { categoryId: 1, slug: 'shoes', name: '신발', cachedCount: 12, actualCount: 9, difference: 3, kind: 'OVERCOUNT' },
    { categoryId: 2, slug: 'bags', name: '가방', cachedCount: 4, actualCount: 7, difference: -3, kind: 'UNDERCOUNT' },
  ],
  unreadable: 0,
};

const renderPanel = () => render(<ToastProvider><CategoryCountIntegrityPanel /></ToastProvider>);

const check = () => fireEvent.click(screen.getByRole('button', { name: '상품수 점검' }));

beforeEach(() => {
  vi.clearAllMocks();
  mocked.checkCounts.mockResolvedValue(drifted);
  mocked.refreshCounts.mockResolvedValue(2);
});

describe('CategoryCountIntegrityPanel', () => {
  it('진입만으로는 세지 않는다 — 전수 대조라 운영자가 부를 때 돈다', () => {
    renderPanel();
    expect(mocked.checkCounts).not.toHaveBeenCalled();
  });

  it('일치하면 그대로 알리고 재계산 버튼을 내지 않는다', async () => {
    mocked.checkCounts.mockResolvedValue(healthy);
    renderPanel();
    check();

    await waitFor(() => expect(screen.getByText(/캐시와 실계수가 일치합니다/)).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '재계산' })).not.toBeInTheDocument();
  });

  it('규모와 방향, 표본을 함께 보여 준다 — 표본 수를 규모로 읽으면 우선순위가 틀어진다', async () => {
    renderPanel();
    check();

    await waitFor(() => expect(screen.getByText(/97건/)).toBeInTheDocument());
    expect(screen.getByText(/과다 1건/)).toBeInTheDocument();
    expect(screen.getByText(/과소 1건/)).toBeInTheDocument();
    expect(screen.getByText(/표본 2건/)).toBeInTheDocument();
    expect(screen.getByText('신발')).toBeInTheDocument();
  });

  it('재계산하면 다시 세어 결과를 갱신한다 — 고쳤다는 말 대신 다시 센 숫자를 보여 준다', async () => {
    renderPanel();
    check();
    await waitFor(() => expect(mocked.checkCounts).toHaveBeenCalledTimes(1));

    mocked.checkCounts.mockResolvedValue(healthy);
    fireEvent.click(await screen.findByRole('button', { name: '재계산' }));

    await waitFor(() => expect(mocked.refreshCounts).toHaveBeenCalled());
    await waitFor(() => expect(mocked.checkCounts).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByText(/캐시와 실계수가 일치합니다/)).toBeInTheDocument());
  });

  it('읽을 수 없는 행이 있으면 조회 조건 자체를 의심하라고 드러낸다', async () => {
    mocked.checkCounts.mockResolvedValue({ ...drifted, unreadable: 3 });
    renderPanel();
    check();

    await waitFor(() => expect(screen.getByText(/드리프트로 인정되지 않은 행 3건/)).toBeInTheDocument());
  });

  it('점검 실패는 화면에 남긴다', async () => {
    mocked.checkCounts.mockRejectedValue(new Error('boom'));
    renderPanel();
    check();

    await waitFor(() => expect(screen.getByText(/상품수 정합을 점검하지 못했습니다|boom/)).toBeInTheDocument());
  });
});
