import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import InventoryTab from '@/components/product/InventoryTab';
import { productApi } from '@/api/product';

const showToast = vi.fn();

vi.mock('@/api/product', () => ({
  productApi: {
    getAllProducts: vi.fn(),
    updateProductStock: vi.fn(),
    activateProduct: vi.fn(),
    deactivateProduct: vi.fn(),
    discontinueProduct: vi.fn(),
  },
}));

vi.mock('@/contexts/useToast', () => ({
  useToast: () => ({ showToast }),
}));

const product = (over: Record<string, unknown> = {}) =>
  ({
    id: 1,
    name: '티셔츠',
    description: '면 100%',
    price: 19900,
    stockQuantity: 50,
    status: 'ACTIVE',
    primaryImageUrl: null,
    ...over,
  }) as never;

const products = [
  product({ id: 1, name: '티셔츠', stockQuantity: 50, price: 19900, status: 'ACTIVE' }),
  product({ id: 2, name: '바지', stockQuantity: 5, price: 39900, status: 'ACTIVE' }),
  product({ id: 3, name: '모자', stockQuantity: 0, price: 9900, status: 'OUT_OF_STOCK' }),
  product({ id: 4, name: '양말', stockQuantity: 20, price: 2900, status: 'INACTIVE', description: null }),
  product({ id: 5, name: '가방', stockQuantity: 3, price: 59900, status: 'DISCONTINUED' }),
];

const rowNames = () =>
  screen
    .getAllByRole('row')
    .slice(1) // 헤더 제외
    .map((r) => within(r).getAllByRole('cell')[0].textContent ?? '');

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(productApi.getAllProducts).mockResolvedValue(products);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const renderTab = async () => {
  render(<InventoryTab />);
  await waitFor(() => expect(screen.queryByText('재고 현황 불러오는 중...')).not.toBeInTheDocument());
};

describe('InventoryTab — 로딩·요약', () => {
  it('불러오는 동안 스피너를 보여 준다', () => {
    render(<InventoryTab />);

    expect(screen.getByText('재고 현황 불러오는 중...')).toBeInTheDocument();
  });

  it('요약 카드에 전체·판매중·저재고·품절 수를 센다', async () => {
    await renderTab();

    // 요약 카드는 sub 문구로 특정한다 — '판매 중'·'품절'은 필터 버튼·상태 배지에도 있어 중복된다.
    const cardBySub = (sub: string) =>
      screen.getByText(sub).parentElement?.textContent ?? '';
    expect(screen.getByText('전체 상품').parentElement?.textContent).toContain('5');
    expect(cardBySub('재고 있는 ACTIVE 상품')).toContain('2'); // ACTIVE & 재고>0
    expect(cardBySub('재고 1~10개')).toContain('2'); // 바지5, 가방3
    expect(cardBySub('재고 0개')).toContain('1');
  });

  it('테이블에 상품과 하단 카운트를 그린다', async () => {
    await renderTab();

    expect(screen.getByText('티셔츠')).toBeInTheDocument();
    expect(screen.getByText('5개 상품 표시 중 (전체 5개)')).toBeInTheDocument();
    expect(screen.getByText('ID #1')).toBeInTheDocument();
  });

  it('재고 상태 문구를 재고량에 따라 다르게 붙인다', async () => {
    await renderTab();

    expect(screen.getAllByText('저재고 주의')).toHaveLength(2); // 바지5, 가방3
    expect(screen.getAllByText('품절').length).toBeGreaterThan(0);
  });

  it('새로고침 버튼이 목록을 다시 부른다', async () => {
    await renderTab();

    await userEvent.click(screen.getByTitle('새로고침'));

    await waitFor(() => expect(productApi.getAllProducts).toHaveBeenCalledTimes(2));
  });
});

describe('InventoryTab — 검색·필터', () => {
  it('상품명으로 검색한다', async () => {
    await renderTab();

    await userEvent.type(screen.getByPlaceholderText(/상품명 또는 설명/), '모자');

    expect(rowNames().join()).toContain('모자');
    expect(rowNames()).toHaveLength(1);
  });

  it('설명으로도 검색된다', async () => {
    await renderTab();

    await userEvent.type(screen.getByPlaceholderText(/상품명 또는 설명/), '면 100%');

    expect(rowNames().length).toBeGreaterThan(0);
  });

  it('일치가 없으면 빈 상태 문구를 보여 준다', async () => {
    await renderTab();

    await userEvent.type(screen.getByPlaceholderText(/상품명 또는 설명/), '없는상품');

    expect(screen.getByText('조건에 맞는 상품이 없습니다.')).toBeInTheDocument();
  });

  it('판매 중 필터는 ACTIVE + 재고>0 만 남긴다', async () => {
    await renderTab();

    await userEvent.click(screen.getByRole('button', { name: '판매 중' }));

    expect(rowNames()).toHaveLength(2);
  });

  it('저재고 필터는 1~10개만 남긴다', async () => {
    await renderTab();

    await userEvent.click(screen.getByRole('button', { name: /저재고/ }));

    expect(rowNames()).toHaveLength(2);
  });

  it('품절 필터는 재고 0 만 남긴다', async () => {
    await renderTab();

    await userEvent.click(screen.getByRole('button', { name: /^품절/ }));

    expect(rowNames()).toHaveLength(1);
  });

  it('비활성/단종 필터는 INACTIVE·DISCONTINUED 를 남긴다', async () => {
    await renderTab();

    await userEvent.click(screen.getByRole('button', { name: '비활성/단종' }));

    expect(rowNames()).toHaveLength(2);
  });

  it('전체 필터로 되돌릴 수 있다', async () => {
    await renderTab();

    await userEvent.click(screen.getByRole('button', { name: /^품절/ }));
    await userEvent.click(screen.getByRole('button', { name: '전체' }));

    expect(rowNames()).toHaveLength(5);
  });
});

describe('InventoryTab — 정렬', () => {
  it('기본은 재고 오름차순이다', async () => {
    await renderTab();

    expect(rowNames()[0]).toContain('모자'); // 재고 0
  });

  /** 헤더 셀 순서: 상품 · 현재 재고 · 가격 · 상태 · 재고 조정 · 상태 변경 */
  const header = (i: number) => screen.getAllByRole('columnheader')[i];

  it('같은 열을 다시 누르면 방향이 뒤집힌다', async () => {
    await renderTab();

    await userEvent.click(header(1));

    expect(rowNames()[0]).toContain('티셔츠'); // 재고 50
    expect(screen.getByText(/정렬: stock/)).toBeInTheDocument();
  });

  it('상품명 정렬', async () => {
    await renderTab();

    await userEvent.click(header(0));

    expect(screen.getByText(/정렬: name/)).toBeInTheDocument();
  });

  it('가격 정렬', async () => {
    await renderTab();

    await userEvent.click(header(2));

    expect(rowNames()[0]).toContain('양말'); // 2900원
  });

  it('상태 정렬', async () => {
    await renderTab();

    await userEvent.click(header(3));

    expect(screen.getByText(/정렬: status/)).toBeInTheDocument();
  });
});

describe('InventoryTab — 재고 조정', () => {
  const firstRow = () => screen.getAllByRole('row')[1];

  it('수량 없이 입고를 누르면 버튼이 잠겨 있다', async () => {
    await renderTab();

    expect(within(firstRow()).getByRole('button', { name: '+ 입고' })).toBeDisabled();
  });

  it('품절 상품은 출고 버튼이 잠긴다', async () => {
    await renderTab();

    const row = firstRow(); // 재고 0 (모자)
    await userEvent.type(within(row).getByPlaceholderText('수량'), '1');

    expect(within(row).getByRole('button', { name: '- 출고' })).toBeDisabled();
  });

  it('0 이하 수량은 토스트로 막는다', async () => {
    await renderTab();

    const row = firstRow();
    await userEvent.type(within(row).getByPlaceholderText('수량'), '0');
    await userEvent.click(within(row).getByRole('button', { name: '+ 입고' }));

    expect(showToast).toHaveBeenCalledWith('수량을 1 이상 입력해주세요.', 'error');
    expect(productApi.updateProductStock).not.toHaveBeenCalled();
  });

  it('입고 성공 시 목록의 해당 행만 교체한다', async () => {
    vi.mocked(productApi.updateProductStock).mockResolvedValueOnce(
      product({ id: 3, name: '모자', stockQuantity: 7, status: 'ACTIVE' }),
    );
    await renderTab();

    const row = firstRow();
    await userEvent.type(within(row).getByPlaceholderText('수량'), '7');
    await userEvent.click(within(row).getByRole('button', { name: '+ 입고' }));

    await waitFor(() =>
      expect(productApi.updateProductStock).toHaveBeenCalledWith(3, {
        quantity: 7,
        operation: 'INCREASE',
      }),
    );
    expect(showToast).toHaveBeenCalledWith('모자: 7개 입고 완료', 'success');
  });

  it('재고보다 많은 출고는 상한 안내로 막는다', async () => {
    await renderTab();

    await userEvent.click(screen.getByRole('button', { name: '판매 중' }));
    const row = screen.getAllByRole('row')[1]; // 재고 5 (바지)
    await userEvent.type(within(row).getByPlaceholderText('수량'), '99');
    await userEvent.click(within(row).getByRole('button', { name: '- 출고' }));

    expect(showToast).toHaveBeenCalledWith('최대 출고 가능 수량은 5개입니다.', 'error');
    expect(productApi.updateProductStock).not.toHaveBeenCalled();
  });

  it('출고 성공', async () => {
    vi.mocked(productApi.updateProductStock).mockResolvedValueOnce(
      product({ id: 2, name: '바지', stockQuantity: 3 }),
    );
    await renderTab();

    await userEvent.click(screen.getByRole('button', { name: '판매 중' }));
    const row = screen.getAllByRole('row')[1];
    await userEvent.type(within(row).getByPlaceholderText('수량'), '2');
    await userEvent.click(within(row).getByRole('button', { name: '- 출고' }));

    await waitFor(() =>
      expect(productApi.updateProductStock).toHaveBeenCalledWith(2, {
        quantity: 2,
        operation: 'DECREASE',
      }),
    );
    expect(showToast).toHaveBeenCalledWith('바지: 2개 출고 완료', 'success');
  });

  it('Enter 키는 입고로 동작한다', async () => {
    vi.mocked(productApi.updateProductStock).mockResolvedValueOnce(product({ id: 3 }));
    await renderTab();

    const row = firstRow();
    await userEvent.type(within(row).getByPlaceholderText('수량'), '3{Enter}');

    await waitFor(() =>
      expect(productApi.updateProductStock).toHaveBeenCalledWith(3, {
        quantity: 3,
        operation: 'INCREASE',
      }),
    );
  });

  it('조정 실패는 토스트로 알린다', async () => {
    vi.mocked(productApi.updateProductStock).mockRejectedValueOnce(new Error('boom'));
    await renderTab();

    const row = firstRow();
    await userEvent.type(within(row).getByPlaceholderText('수량'), '1');
    await userEvent.click(within(row).getByRole('button', { name: '+ 입고' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('재고 조정에 실패했습니다.', 'error'),
    );
  });
});

describe('InventoryTab — 상태 변경', () => {
  it('ACTIVE 상품은 비활성화·단종 버튼을 보여 준다', async () => {
    await renderTab();
    await userEvent.click(screen.getByRole('button', { name: '판매 중' }));

    const row = screen.getAllByRole('row')[1];
    expect(within(row).getByRole('button', { name: '비활성화' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: '단종' })).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '활성화' })).not.toBeInTheDocument();
  });

  it('INACTIVE 상품은 활성화 버튼을 보여 준다', async () => {
    await renderTab();
    await userEvent.click(screen.getByRole('button', { name: '비활성/단종' }));

    expect(screen.getByRole('button', { name: '활성화' })).toBeInTheDocument();
  });

  it('DISCONTINUED 상품은 재판매 버튼을 보여 주고 단종 버튼은 없다', async () => {
    await renderTab();
    await userEvent.click(screen.getByRole('button', { name: '비활성/단종' }));

    expect(screen.getByRole('button', { name: '재판매' })).toBeInTheDocument();
    const discontinuedRow = screen
      .getAllByRole('row')
      .find((r) => within(r).queryByText('가방')) as HTMLElement;
    expect(within(discontinuedRow).queryByRole('button', { name: '단종' })).not.toBeInTheDocument();
  });

  it('비활성화를 누르면 API 를 호출하고 결과를 반영한다', async () => {
    vi.mocked(productApi.deactivateProduct).mockResolvedValueOnce(
      product({ id: 2, name: '바지', status: 'INACTIVE', stockQuantity: 5 }),
    );
    await renderTab();
    await userEvent.click(screen.getByRole('button', { name: '판매 중' }));

    const row = screen.getAllByRole('row')[1];
    await userEvent.click(within(row).getByRole('button', { name: '비활성화' }));

    await waitFor(() => expect(productApi.deactivateProduct).toHaveBeenCalledWith(2));
    expect(showToast).toHaveBeenCalledWith('바지: 비활성화 완료', 'success');
  });

  it('활성화를 누르면 API 를 호출한다', async () => {
    vi.mocked(productApi.activateProduct).mockResolvedValueOnce(
      product({ id: 4, name: '양말', status: 'ACTIVE' }),
    );
    await renderTab();
    await userEvent.click(screen.getByRole('button', { name: '비활성/단종' }));

    await userEvent.click(screen.getByRole('button', { name: '활성화' }));

    await waitFor(() => expect(productApi.activateProduct).toHaveBeenCalledWith(4));
  });

  it('단종은 확인창을 거치고, 취소하면 호출하지 않는다', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(false));
    await renderTab();
    await userEvent.click(screen.getByRole('button', { name: '판매 중' }));

    const row = screen.getAllByRole('row')[1];
    await userEvent.click(within(row).getByRole('button', { name: '단종' }));

    expect(productApi.discontinueProduct).not.toHaveBeenCalled();
  });

  it('확인하면 단종 처리한다', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    vi.mocked(productApi.discontinueProduct).mockResolvedValueOnce(
      product({ id: 2, name: '바지', status: 'DISCONTINUED', stockQuantity: 5 }),
    );
    await renderTab();
    await userEvent.click(screen.getByRole('button', { name: '판매 중' }));

    const row = screen.getAllByRole('row')[1];
    await userEvent.click(within(row).getByRole('button', { name: '단종' }));

    await waitFor(() => expect(productApi.discontinueProduct).toHaveBeenCalledWith(2));
    expect(showToast).toHaveBeenCalledWith('바지: 단종 완료', 'success');
  });

  it('상태 변경 실패는 토스트로 알린다', async () => {
    vi.mocked(productApi.deactivateProduct).mockRejectedValueOnce(new Error('boom'));
    await renderTab();
    await userEvent.click(screen.getByRole('button', { name: '판매 중' }));

    const row = screen.getAllByRole('row')[1];
    await userEvent.click(within(row).getByRole('button', { name: '비활성화' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('상태 변경에 실패했습니다.', 'error'),
    );
  });
});
