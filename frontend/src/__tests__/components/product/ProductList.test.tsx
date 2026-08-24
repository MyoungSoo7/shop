import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ProductList from '@/components/product/ProductList';
import { productApi } from '@/api/product';
import type { ProductResponse } from '@/types';

vi.mock('@/api/product', () => ({
  productApi: {
    getAllProducts: vi.fn(),
  },
}));

const makeProduct = (overrides: Partial<ProductResponse> = {}): ProductResponse => ({
  id: 1,
  name: '테스트 상품',
  description: '상품 설명',
  price: 10000,
  stockQuantity: 100,
  status: 'ACTIVE',
  availableForSale: true,
  createdAt: '2024-01-01T00:00:00',
  updatedAt: '2024-01-01T00:00:00',
  ...overrides,
});

const mockProducts: ProductResponse[] = [
  makeProduct({ id: 1, name: '사과', status: 'ACTIVE', availableForSale: true }),
  makeProduct({ id: 2, name: '바나나', status: 'INACTIVE', availableForSale: false, description: '노란 바나나' }),
  makeProduct({ id: 3, name: '오렌지', status: 'OUT_OF_STOCK', stockQuantity: 0, availableForSale: false }),
  makeProduct({ id: 4, name: '포도', status: 'DISCONTINUED', availableForSale: false }),
];

describe('ProductList', () => {
  const user = userEvent.setup();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ─── 로딩 상태 ───────────────────────────────────────────────
  describe('로딩 상태', () => {
    it('데이터 로딩 중 스피너를 표시한다', () => {
      vi.mocked(productApi.getAllProducts).mockImplementationOnce(
        () => new Promise(() => {})  // 영원히 pending
      );
      render(<ProductList />);

      // 로딩 스피너가 있어야 함 (animate-spin 클래스로 확인)
      expect(document.querySelector('.animate-spin')).toBeInTheDocument();
    });

    it('로딩 완료 후 스피너가 사라진다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce(mockProducts);
      render(<ProductList />);

      await waitFor(() => {
        expect(document.querySelector('.animate-spin')).not.toBeInTheDocument();
      });
    });
  });

  // ─── 상품 목록 렌더링 ────────────────────────────────────────
  describe('상품 목록 렌더링', () => {
    it('상품 목록을 올바르게 렌더링한다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce(mockProducts);
      render(<ProductList />);

      await waitFor(() => {
        expect(screen.getByText('사과')).toBeInTheDocument();
        expect(screen.getByText('바나나')).toBeInTheDocument();
        expect(screen.getByText('오렌지')).toBeInTheDocument();
        expect(screen.getByText('포도')).toBeInTheDocument();
      });
    });

    it('상품 개수 요약 정보를 표시한다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce(mockProducts);
      render(<ProductList />);

      await waitFor(() => {
        expect(screen.getByText(/전체 4개 상품 중 4개 표시/)).toBeInTheDocument();
      });
    });

    it('상품 가격이 원화 형식으로 표시된다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([makeProduct({ price: 10000 })]);
      render(<ProductList />);

      await waitFor(() => {
        expect(screen.getByText('₩10,000')).toBeInTheDocument();
      });
    });

    it('ACTIVE 상품에 "판매중" 배지가 표시된다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([
        makeProduct({ name: '사과', status: 'ACTIVE' }),
      ]);
      render(<ProductList />);

      // 상품 카드가 렌더링될 때까지 대기
      await waitFor(() => screen.getByText('사과'));

      // 상품 카드 내에서 배지 확인 (select 옵션과 구분)
      const card = screen.getByText('사과').closest<HTMLElement>('div.bg-white.rounded-lg');
      expect(within(card!).getByText('판매중')).toBeInTheDocument();
    });

    it('INACTIVE 상품에 "판매중지" 배지가 표시된다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([
        makeProduct({ name: '바나나', status: 'INACTIVE' }),
      ]);
      render(<ProductList />);

      await waitFor(() => screen.getByText('바나나'));

      const card = screen.getByText('바나나').closest<HTMLElement>('div.bg-white.rounded-lg');
      expect(within(card!).getByText('판매중지')).toBeInTheDocument();
    });

    it('OUT_OF_STOCK 상품에 "품절" 배지가 표시된다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([
        makeProduct({ name: '오렌지', status: 'OUT_OF_STOCK' }),
      ]);
      render(<ProductList />);

      await waitFor(() => screen.getByText('오렌지'));

      const card = screen.getByText('오렌지').closest<HTMLElement>('div.bg-white.rounded-lg');
      expect(within(card!).getByText('품절')).toBeInTheDocument();
    });

    it('DISCONTINUED 상품에 "단종" 배지가 표시된다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([
        makeProduct({ name: '포도', status: 'DISCONTINUED' }),
      ]);
      render(<ProductList />);

      await waitFor(() => screen.getByText('포도'));

      const card = screen.getByText('포도').closest<HTMLElement>('div.bg-white.rounded-lg');
      expect(within(card!).getByText('단종')).toBeInTheDocument();
    });

    it('판매 가능한 상품에 판매 가능 표시가 있다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([
        makeProduct({ availableForSale: true }),
      ]);
      render(<ProductList />);

      await waitFor(() => {
        expect(screen.getByText(/판매 가능/)).toBeInTheDocument();
      });
    });

    it('상품이 없으면 빈 상태 메시지를 표시한다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([]);
      render(<ProductList />);

      await waitFor(() => {
        expect(screen.getByText('등록된 상품이 없습니다.')).toBeInTheDocument();
      });
    });
  });

  // ─── 에러 상태 ───────────────────────────────────────────────
  describe('에러 상태', () => {
    it('API 오류 시 에러 메시지를 표시한다', async () => {
      vi.mocked(productApi.getAllProducts).mockRejectedValueOnce({
        response: { data: { message: '상품 목록을 불러오는데 실패했습니다.' } },
      });
      render(<ProductList />);

      await waitFor(() => {
        expect(screen.getByText('상품 목록을 불러오는데 실패했습니다.')).toBeInTheDocument();
      });
    });

    it('서버 메시지가 없으면 기본 에러 메시지를 표시한다', async () => {
      vi.mocked(productApi.getAllProducts).mockRejectedValueOnce(new Error('Network Error'));
      render(<ProductList />);

      await waitFor(() => {
        expect(screen.getByText('상품 목록을 불러오는데 실패했습니다.')).toBeInTheDocument();
      });
    });

    it('"다시 시도" 버튼 클릭 시 목록을 재조회한다', async () => {
      vi.mocked(productApi.getAllProducts)
        .mockRejectedValueOnce(new Error('Network Error'))
        .mockResolvedValueOnce(mockProducts);

      render(<ProductList />);

      await waitFor(() => {
        expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
      });

      await user.click(screen.getByRole('button', { name: '다시 시도' }));

      await waitFor(() => {
        expect(screen.getByText('사과')).toBeInTheDocument();
      });
      expect(productApi.getAllProducts).toHaveBeenCalledTimes(2);
    });
  });

  // ─── 검색 필터링 ─────────────────────────────────────────────
  describe('검색 필터링', () => {
    beforeEach(async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValue(mockProducts);
    });

    it('상품명으로 검색하면 결과가 필터링된다', async () => {
      render(<ProductList />);
      await waitFor(() => screen.getByText('사과'));

      await user.type(screen.getByPlaceholderText('상품명 또는 설명으로 검색...'), '바나나');

      expect(screen.getByText('바나나')).toBeInTheDocument();
      expect(screen.queryByText('사과')).not.toBeInTheDocument();
    });

    it('설명으로도 검색이 된다', async () => {
      render(<ProductList />);
      await waitFor(() => screen.getByText('사과'));

      await user.type(screen.getByPlaceholderText('상품명 또는 설명으로 검색...'), '노란');

      expect(screen.getByText('바나나')).toBeInTheDocument();
      expect(screen.queryByText('사과')).not.toBeInTheDocument();
    });

    it('검색은 대소문자를 구분하지 않는다', async () => {
      const products = [makeProduct({ name: 'Apple', description: 'red apple' })];
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce(products);
      render(<ProductList />);
      await waitFor(() => screen.getByText('Apple'));

      await user.type(screen.getByPlaceholderText('상품명 또는 설명으로 검색...'), 'apple');

      expect(screen.getByText('Apple')).toBeInTheDocument();
    });

    it('검색 결과가 없으면 "검색 조건에 맞는 상품이 없습니다." 메시지를 표시한다', async () => {
      render(<ProductList />);
      await waitFor(() => screen.getByText('사과'));

      await user.type(screen.getByPlaceholderText('상품명 또는 설명으로 검색...'), '존재하지않는상품');

      expect(screen.getByText('검색 조건에 맞는 상품이 없습니다.')).toBeInTheDocument();
    });
  });

  // ─── 상태 필터링 ─────────────────────────────────────────────
  describe('상태 필터링', () => {
    beforeEach(async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValue(mockProducts);
    });

    it('"판매중" 선택 시 ACTIVE 상품만 표시된다', async () => {
      render(<ProductList />);
      await waitFor(() => screen.getByText('사과'));

      await user.selectOptions(screen.getByRole('combobox'), 'ACTIVE');

      expect(screen.getByText('사과')).toBeInTheDocument();
      expect(screen.queryByText('바나나')).not.toBeInTheDocument();
      expect(screen.queryByText('오렌지')).not.toBeInTheDocument();
      expect(screen.queryByText('포도')).not.toBeInTheDocument();
    });

    it('"품절" 선택 시 OUT_OF_STOCK 상품만 표시된다', async () => {
      render(<ProductList />);
      await waitFor(() => screen.getByText('사과'));

      await user.selectOptions(screen.getByRole('combobox'), 'OUT_OF_STOCK');

      expect(screen.getByText('오렌지')).toBeInTheDocument();
      expect(screen.queryByText('사과')).not.toBeInTheDocument();
    });

    it('"전체 상태" 선택 시 모든 상품이 표시된다', async () => {
      render(<ProductList />);
      await waitFor(() => screen.getByText('사과'));

      await user.selectOptions(screen.getByRole('combobox'), 'ACTIVE');
      await user.selectOptions(screen.getByRole('combobox'), 'ALL');

      expect(screen.getByText('사과')).toBeInTheDocument();
      expect(screen.getByText('바나나')).toBeInTheDocument();
      expect(screen.getByText('오렌지')).toBeInTheDocument();
      expect(screen.getByText('포도')).toBeInTheDocument();
    });

    it('상태 필터링 후 카운트가 업데이트된다', async () => {
      render(<ProductList />);
      await waitFor(() => screen.getByText(/전체 4개 상품 중 4개 표시/));

      await user.selectOptions(screen.getByRole('combobox'), 'ACTIVE');

      expect(screen.getByText(/전체 4개 상품 중 1개 표시/)).toBeInTheDocument();
    });
  });

  // ─── 상품 클릭 이벤트 ────────────────────────────────────────
  describe('상품 클릭 이벤트', () => {
    it('상품 카드 클릭 시 onProductSelect 콜백이 호출된다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([mockProducts[0]]);
      const onProductSelect = vi.fn();
      render(<ProductList onProductSelect={onProductSelect} />);

      await waitFor(() => screen.getByText('사과'));
      await user.click(screen.getByText('사과').closest('div[class*="cursor-pointer"]')!);

      expect(onProductSelect).toHaveBeenCalledWith(mockProducts[0]);
    });

    it('onProductSelect가 없어도 클릭 시 에러가 발생하지 않는다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValueOnce([mockProducts[0]]);
      render(<ProductList />);

      await waitFor(() => screen.getByText('사과'));

      // 에러 없이 클릭이 가능해야 함
      await expect(
        user.click(screen.getByText('사과').closest('div[class*="cursor-pointer"]')!)
      ).resolves.not.toThrow();
    });
  });

  // ─── refreshTrigger ──────────────────────────────────────────
  describe('refreshTrigger', () => {
    it('refreshTrigger 변경 시 상품 목록을 재조회한다', async () => {
      vi.mocked(productApi.getAllProducts).mockResolvedValue(mockProducts);
      const { rerender } = render(<ProductList refreshTrigger={0} />);

      await waitFor(() => screen.getByText('사과'));
      expect(productApi.getAllProducts).toHaveBeenCalledTimes(1);

      rerender(<ProductList refreshTrigger={1} />);

      await waitFor(() => {
        expect(productApi.getAllProducts).toHaveBeenCalledTimes(2);
      });
    });
  });
});