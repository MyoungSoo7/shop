import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SalesStatsPage from '@/pages/system/SalesStatsPage';
import { salesStatsApi, type ProductRanking, type CategoryBreakdown } from '@/api/salesStats';

vi.mock('@/api/salesStats', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/salesStats')>();
  return {
    ...actual,
    salesStatsApi: {
      topProducts: vi.fn(), byCategory: vi.fn(),
      exportProducts: vi.fn(), exportCategories: vi.fn(),
    },
  };
});
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const mocked = vi.mocked(salesStatsApi);

const ranking = (over: Partial<ProductRanking> = {}): ProductRanking => ({
  from: '2026-07-25', to: '2026-08-23', statuses: ['PAID'], limit: 20,
  rows: [
    { productId: 11, productName: '칫솔', quantity: 40, netAmount: 400000, orderCount: 30 },
    { productId: 12, productName: '치약', quantity: 20, netAmount: 200000, orderCount: 15 },
  ],
  // 표의 두 행 합계는 600,000 이고 전 범위는 1,000,000 — 표가 덮는 것은 60% 다.
  total: { quantity: 100, netAmount: 1000000, lineCount: 90, orderCount: 80 },
  ...over,
});

const breakdown = (over: Partial<CategoryBreakdown> = {}): CategoryBreakdown => ({
  from: '2026-07-25', to: '2026-08-23', statuses: ['PAID'],
  rows: [
    { categoryId: 3, categoryName: '구강용품', pathSlug: 'health/oral', depth: 2, quantity: 60, netAmount: 600000, orderCount: 50 },
    { categoryId: null, categoryName: null, pathSlug: null, depth: null, quantity: 40, netAmount: 400000, orderCount: 30 },
  ],
  total: { quantity: 100, netAmount: 1000000, lineCount: 90, orderCount: 80 },
  ...over,
});

beforeEach(() => vi.clearAllMocks());

/**
 * 이 화면의 유일한 진짜 위험은 <b>표의 합계를 전체 매출로 읽는 것</b>이다. 랭킹은 본래 잘라낸
 * 목록이라, 표만 보면 언제나 실제보다 작은 매출을 본다. 그래서 검증의 초점은 숫자 포맷이 아니라
 * 전 범위 합계가 표보다 <b>먼저</b> 오는가와, 표가 그중 얼마를 덮는지 적혀 있는가다.
 */
describe('SalesStatsPage — 잘린 목록임을 숨기지 않는다', () => {
  it('전 범위 합계를 표보다 먼저 놓는다', async () => {
    mocked.topProducts.mockResolvedValue(ranking());
    render(<SalesStatsPage />);
    await waitFor(() => expect(screen.getByTestId('product-table')).toBeInTheDocument());

    // 표 아래에 두면 행 합계를 먼저 읽는다 — 순서 자체가 이 화면의 안전장치다.
    expect(screen.getByTestId('sales-total').compareDocumentPosition(screen.getByTestId('product-table')))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('표가 전 범위의 몇 %를 덮는지 적는다', async () => {
    mocked.topProducts.mockResolvedValue(ranking());
    render(<SalesStatsPage />);

    await waitFor(() => expect(screen.getByTestId('sales-coverage')).toHaveTextContent('60%'));
  });

  it('CSV 가 잘렸으면 전 범위 순매출을 함께 말한다', async () => {
    mocked.topProducts.mockResolvedValue(ranking());
    mocked.exportProducts.mockResolvedValue({
      blob: new Blob(['a']), fileName: 'sales_products.csv',
      truncated: true, limit: 20, netAmount: 1000000, range: '2026-07-25~2026-08-23',
    });
    render(<SalesStatsPage />);
    await waitFor(() => expect(screen.getByTestId('product-table')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'CSV' }));

    expect(await screen.findByTestId('sales-notice')).toHaveTextContent('상위 20개만 담겼습니다');
  });
});

describe('SalesStatsPage — 카테고리 분포', () => {
  it('대표 분류가 없는 매출을 버리지 않고 미분류로 남긴다', async () => {
    mocked.topProducts.mockResolvedValue(ranking());
    mocked.byCategory.mockResolvedValue(breakdown());
    render(<SalesStatsPage />);
    await waitFor(() => expect(screen.getByTestId('product-table')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('tab', { name: '카테고리 분포' }));

    // 빼면 분포의 합이 총액에 못 미치는 것을 볼 사람이 없다.
    expect(await screen.findByTestId('category-row-unclassified')).toHaveTextContent('미분류');
    expect(screen.getByTestId('category-row-3')).toHaveTextContent('구강용품');
  });

  it('카테고리 탭에는 상위 N 이 없다 — 분포는 자르지 않는다', async () => {
    mocked.topProducts.mockResolvedValue(ranking());
    mocked.byCategory.mockResolvedValue(breakdown());
    render(<SalesStatsPage />);
    await waitFor(() => expect(screen.getByLabelText('상위 개수')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('tab', { name: '카테고리 분포' }));

    await waitFor(() => expect(screen.queryByLabelText('상위 개수')).not.toBeInTheDocument());
    expect(mocked.byCategory).toHaveBeenCalledWith(
      expect.not.objectContaining({ limit: expect.anything() }));
  });
});

describe('SalesStatsPage — 조회 실패와 0건', () => {
  it('조회가 실패하면 빈 표가 아니라 오류를 보여 준다', async () => {
    mocked.topProducts.mockRejectedValue(new Error('boom'));
    render(<SalesStatsPage />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    // 빈 표는 실패를 "그 기간에 아무것도 안 팔렸다"로 위장시킨다.
    expect(screen.queryByTestId('product-table')).not.toBeInTheDocument();
    expect(screen.queryByTestId('sales-empty')).not.toBeInTheDocument();
    expect(screen.queryByTestId('sales-total')).not.toBeInTheDocument();
  });

  it('0건이면 없다고 말한다', async () => {
    mocked.topProducts.mockResolvedValue(ranking({
      rows: [], total: { quantity: 0, netAmount: 0, lineCount: 0, orderCount: 0 },
    }));
    render(<SalesStatsPage />);

    await waitFor(() => expect(screen.getByTestId('sales-empty')).toBeInTheDocument());
    // 전체가 0 이면 비율 자체가 뜻이 없다 — 0% 라고 적지 않는다.
    expect(screen.queryByTestId('sales-coverage')).not.toBeInTheDocument();
  });
});
