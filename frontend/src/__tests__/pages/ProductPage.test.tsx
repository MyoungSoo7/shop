import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ProductPage from '@/pages/ProductPage';
import { productApi } from '@/api/product';
import api from '@/api/axios';

const showToast = vi.fn();

vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

vi.mock('@/api/axios', () => ({ default: { get: vi.fn() } }));

vi.mock('@/api/product', () => ({
  productApi: {
    getAllProducts: vi.fn(),
    updateProductInfo: vi.fn(),
    updateProductPrice: vi.fn(),
    updateProductStock: vi.fn(),
    discontinueProduct: vi.fn(),
    activateProduct: vi.fn(),
    deactivateProduct: vi.fn(),
  },
}));

// 목록·등록 폼·재고 탭은 각각 자체 테스트가 있다 — 여기서는 페이지의 탭 전환과 상세 편집만 본다.
vi.mock('@/components/product/ProductList', () => ({
  default: ({ onProductSelect }: { onProductSelect: (p: unknown) => void }) => (
    <button
      onClick={() =>
        onProductSelect({
          id: 1,
          name: '티셔츠',
          description: '면 100%',
          price: 20000,
          stockQuantity: 10,
          status: 'ACTIVE',
        })
      }
    >
      상품 선택(스텁)
    </button>
  ),
}));
vi.mock('@/components/product/CreateProductForm', () => ({
  default: ({ onSuccess }: { onSuccess: () => void }) => (
    <button onClick={onSuccess}>등록 완료(스텁)</button>
  ),
}));
vi.mock('@/components/product/InventoryTab', () => ({
  default: () => <div>재고 탭(스텁)</div>,
}));
vi.mock('@/components/product/ImageUpload', () => ({
  default: () => <div>이미지 업로드(스텁)</div>,
}));

const mockedProduct = vi.mocked(productApi);
const mockedApi = vi.mocked(api);

let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedApi.get.mockResolvedValue({ data: [] } as never);
  mockedProduct.updateProductInfo.mockResolvedValue({} as never);
  mockedProduct.updateProductPrice.mockResolvedValue({} as never);
  mockedProduct.updateProductStock.mockResolvedValue({} as never);
  mockedProduct.discontinueProduct.mockResolvedValue({} as never);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

afterEach(() => confirmSpy.mockRestore());

const selectProduct = async () => {
  render(<ProductPage />);
  await userEvent.click(screen.getByRole('button', { name: '상품 선택(스텁)' }));
};

describe('ProductPage — 탭', () => {
  it('기본은 목록 탭이다', () => {
    render(<ProductPage />);

    expect(screen.getByText('상품 목록')).toBeInTheDocument();
  });

  it('등록 탭으로 갔다가 등록이 끝나면 목록으로 돌아온다', async () => {
    render(<ProductPage />);

    await userEvent.click(screen.getByRole('button', { name: '➕ 상품 등록' }));
    expect(screen.getByRole('button', { name: '등록 완료(스텁)' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '등록 완료(스텁)' }));
    expect(screen.getByText('상품 목록')).toBeInTheDocument();
  });

  it('재고 탭으로 전환된다', async () => {
    render(<ProductPage />);

    await userEvent.click(screen.getByRole('button', { name: '📊 재고 관리' }));

    expect(screen.getByText('재고 탭(스텁)')).toBeInTheDocument();
  });
});

describe('ProductPage — 상세·수정', () => {
  it('상품을 고르면 상세와 이미지 목록을 읽는다', async () => {
    await selectProduct();

    expect(await screen.findByText('상품 ID')).toBeInTheDocument();
    expect(mockedApi.get).toHaveBeenCalledWith('/admin/products/1/images');
  });

  it('이미지 조회가 실패해도 상세는 열린다', async () => {
    mockedApi.get.mockRejectedValue(new Error('down'));
    await selectProduct();

    expect(await screen.findByText('상품 ID')).toBeInTheDocument();
  });

  it('수정에서 이름만 바꾸면 가격·재고 API 는 호출하지 않는다', async () => {
    await selectProduct();
    await screen.findByText('상품 ID');

    await userEvent.click(screen.getByRole('button', { name: '수정' }));
    const nameInput = screen.getByDisplayValue('티셔츠');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, '반팔 티셔츠');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mockedProduct.updateProductInfo).toHaveBeenCalledWith(1, {
        name: '반팔 티셔츠',
        description: '면 100%',
      }),
    );
    expect(mockedProduct.updateProductPrice).not.toHaveBeenCalled();
    expect(mockedProduct.updateProductStock).not.toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith('상품이 수정되었습니다.', 'success');
  });

  it('가격을 바꾸면 가격 API 를 부른다', async () => {
    await selectProduct();
    await screen.findByText('상품 ID');
    await userEvent.click(screen.getByRole('button', { name: '수정' }));

    const priceInput = screen.getByDisplayValue('20000');
    await userEvent.clear(priceInput);
    await userEvent.type(priceInput, '25000');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mockedProduct.updateProductPrice).toHaveBeenCalledWith(1, { newPrice: 25000 }),
    );
  });

  it('재고를 늘리면 증가 연산으로 차이만큼 보낸다', async () => {
    await selectProduct();
    await screen.findByText('상품 ID');
    await userEvent.click(screen.getByRole('button', { name: '수정' }));

    const stockInput = screen.getByDisplayValue('10');
    await userEvent.clear(stockInput);
    await userEvent.type(stockInput, '15');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mockedProduct.updateProductStock).toHaveBeenCalledWith(1, {
        quantity: 5,
        operation: 'INCREASE',
      }),
    );
  });

  it('재고를 줄이면 감소 연산으로 보낸다', async () => {
    await selectProduct();
    await screen.findByText('상품 ID');
    await userEvent.click(screen.getByRole('button', { name: '수정' }));

    const stockInput = screen.getByDisplayValue('10');
    await userEvent.clear(stockInput);
    await userEvent.type(stockInput, '4');
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(mockedProduct.updateProductStock).toHaveBeenCalledWith(1, {
        quantity: 6,
        operation: 'DECREASE',
      }),
    );
  });

  it('수정 실패는 토스트로 알린다', async () => {
    mockedProduct.updateProductInfo.mockRejectedValue(new Error('down'));
    await selectProduct();
    await screen.findByText('상품 ID');
    await userEvent.click(screen.getByRole('button', { name: '수정' }));

    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('상품 수정에 실패했습니다.', 'error'));
  });

  it('수정 취소는 입력을 원래 값으로 되돌린다', async () => {
    await selectProduct();
    await screen.findByText('상품 ID');
    await userEvent.click(screen.getByRole('button', { name: '수정' }));
    const nameInput = screen.getByDisplayValue('티셔츠');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, '바뀐 이름');

    await userEvent.click(screen.getByRole('button', { name: '취소' }));
    await userEvent.click(screen.getByRole('button', { name: '수정' }));

    expect(screen.getByDisplayValue('티셔츠')).toBeInTheDocument();
  });

  it('삭제는 확인을 거쳐 단종 처리한다', async () => {
    await selectProduct();
    await screen.findByText('상품 ID');

    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mockedProduct.discontinueProduct).toHaveBeenCalledWith(1));
    expect(showToast).toHaveBeenCalledWith('상품이 단종 처리되었습니다.', 'success');
  });

  it('삭제 확인을 취소하면 호출하지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    await selectProduct();
    await screen.findByText('상품 ID');

    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    expect(mockedProduct.discontinueProduct).not.toHaveBeenCalled();
  });

  it('삭제 실패는 토스트로 알린다', async () => {
    mockedProduct.discontinueProduct.mockRejectedValue(new Error('down'));
    await selectProduct();
    await screen.findByText('상품 ID');

    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('상품 삭제에 실패했습니다.', 'error'));
  });

  it('닫기를 누르면 상세를 접는다', async () => {
    await selectProduct();
    await screen.findByText('상품 ID');

    await userEvent.click(screen.getByRole('button', { name: '닫기' }));

    expect(screen.queryByText('상품 ID')).not.toBeInTheDocument();
  });
});
