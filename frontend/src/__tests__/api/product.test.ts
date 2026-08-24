import { describe, it, expect, vi, beforeEach } from 'vitest';
import { productApi } from '@/api/product';
import api from '@/api/axios';
import type {
  ProductCreateRequest,
  ProductResponse,
  UpdateProductInfoRequest,
  UpdateProductPriceRequest,
  UpdateProductStockRequest,
} from '@/types';

vi.mock('@/api/axios', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
  },
}));

const mockProduct: ProductResponse = {
  id: 1,
  name: '테스트 상품',
  description: '테스트 설명',
  price: 10000,
  stockQuantity: 100,
  status: 'ACTIVE',
  availableForSale: true,
  createdAt: '2024-01-01T00:00:00',
  updatedAt: '2024-01-01T00:00:00',
};

describe('productApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ─── createProduct ───────────────────────────────────────────
  describe('createProduct', () => {
    it('상품을 생성하고 ProductResponse를 반환한다', async () => {
      const request: ProductCreateRequest = {
        name: '테스트 상품',
        description: '테스트 설명',
        price: 10000,
        stockQuantity: 100,
      };
      vi.mocked(api.post).mockResolvedValueOnce({ data: mockProduct });

      const result = await productApi.createProduct(request);

      expect(api.post).toHaveBeenCalledWith('/api/products', request);
      expect(result).toEqual(mockProduct);
    });

    it('설명 없이 상품을 생성할 수 있다', async () => {
      const request: ProductCreateRequest = {
        name: '설명 없는 상품',
        price: 5000,
        stockQuantity: 50,
      };
      const expectedResponse: ProductResponse = {
        ...mockProduct,
        name: '설명 없는 상품',
        description: undefined,
        price: 5000,
        stockQuantity: 50,
      };
      vi.mocked(api.post).mockResolvedValueOnce({ data: expectedResponse });

      const result = await productApi.createProduct(request);

      expect(api.post).toHaveBeenCalledWith('/api/products', request);
      expect(result.description).toBeUndefined();
    });

    it('API 오류 시 에러를 throw한다', async () => {
      const error = new Error('Network Error');
      vi.mocked(api.post).mockRejectedValueOnce(error);

      await expect(productApi.createProduct({ name: '상품', price: 1000, stockQuantity: 1 }))
        .rejects.toThrow('Network Error');
    });
  });

  // ─── getProduct ──────────────────────────────────────────────
  describe('getProduct', () => {
    it('ID로 상품을 조회한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: mockProduct });

      const result = await productApi.getProduct(1);

      expect(api.get).toHaveBeenCalledWith('/api/products/1');
      expect(result).toEqual(mockProduct);
    });

    it('존재하지 않는 ID 조회 시 에러를 throw한다', async () => {
      const error = { response: { status: 404, data: { message: '상품을 찾을 수 없습니다.' } } };
      vi.mocked(api.get).mockRejectedValueOnce(error);

      await expect(productApi.getProduct(999)).rejects.toMatchObject({
        response: { status: 404 },
      });
    });
  });

  // ─── getAllProducts ───────────────────────────────────────────
  describe('getAllProducts', () => {
    it('전체 상품 목록을 반환한다', async () => {
      const productList = [mockProduct, { ...mockProduct, id: 2, name: '상품 2' }];
      vi.mocked(api.get).mockResolvedValueOnce({ data: productList });

      const result = await productApi.getAllProducts();

      expect(api.get).toHaveBeenCalledWith('/api/products');
      expect(result).toHaveLength(2);
      expect(result[0]).toEqual(mockProduct);
    });

    it('상품이 없으면 빈 배열을 반환한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: [] });

      const result = await productApi.getAllProducts();

      expect(result).toEqual([]);
    });
  });

  // ─── getProductsByStatus ──────────────────────────────────────
  describe('getProductsByStatus', () => {
    it('ACTIVE 상태 상품 목록을 반환한다', async () => {
      const activeProducts = [mockProduct];
      vi.mocked(api.get).mockResolvedValueOnce({ data: activeProducts });

      const result = await productApi.getProductsByStatus('ACTIVE');

      expect(api.get).toHaveBeenCalledWith('/api/products/status/ACTIVE');
      expect(result).toEqual(activeProducts);
    });

    it('DISCONTINUED 상태 상품 목록을 반환한다', async () => {
      const discontinued = [{ ...mockProduct, status: 'DISCONTINUED' as const, availableForSale: false }];
      vi.mocked(api.get).mockResolvedValueOnce({ data: discontinued });

      const result = await productApi.getProductsByStatus('DISCONTINUED');

      expect(api.get).toHaveBeenCalledWith('/api/products/status/DISCONTINUED');
      expect(result[0].status).toBe('DISCONTINUED');
    });
  });

  // ─── getAvailableProducts ─────────────────────────────────────
  describe('getAvailableProducts', () => {
    it('판매 가능한 상품 목록을 반환한다', async () => {
      const available = [mockProduct];
      vi.mocked(api.get).mockResolvedValueOnce({ data: available });

      const result = await productApi.getAvailableProducts();

      expect(api.get).toHaveBeenCalledWith('/api/products/available');
      expect(result.every(p => p.availableForSale)).toBe(true);
    });
  });

  // ─── updateProductInfo ────────────────────────────────────────
  describe('updateProductInfo', () => {
    it('상품 이름과 설명을 수정한다', async () => {
      const request: UpdateProductInfoRequest = { name: '수정된 상품명', description: '수정된 설명' };
      const updated = { ...mockProduct, ...request };
      vi.mocked(api.put).mockResolvedValueOnce({ data: updated });

      const result = await productApi.updateProductInfo(1, request);

      expect(api.put).toHaveBeenCalledWith('/api/products/1/info', request);
      expect(result.name).toBe('수정된 상품명');
    });

    it('이름만 수정할 수 있다', async () => {
      const request: UpdateProductInfoRequest = { name: '이름만 변경' };
      vi.mocked(api.put).mockResolvedValueOnce({ data: { ...mockProduct, name: '이름만 변경' } });

      const result = await productApi.updateProductInfo(1, request);

      expect(result.name).toBe('이름만 변경');
    });
  });

  // ─── updateProductPrice ───────────────────────────────────────
  describe('updateProductPrice', () => {
    it('상품 가격을 수정한다', async () => {
      const request: UpdateProductPriceRequest = { newPrice: 20000 };
      const updated = { ...mockProduct, price: 20000 };
      vi.mocked(api.put).mockResolvedValueOnce({ data: updated });

      const result = await productApi.updateProductPrice(1, request);

      expect(api.put).toHaveBeenCalledWith('/api/products/1/price', request);
      expect(result.price).toBe(20000);
    });

    it('가격을 0으로 설정할 수 있다', async () => {
      const request: UpdateProductPriceRequest = { newPrice: 0 };
      vi.mocked(api.put).mockResolvedValueOnce({ data: { ...mockProduct, price: 0 } });

      const result = await productApi.updateProductPrice(1, request);

      expect(result.price).toBe(0);
    });
  });

  // ─── updateProductStock ───────────────────────────────────────
  describe('updateProductStock', () => {
    it('재고를 증가시킨다', async () => {
      const request: UpdateProductStockRequest = { quantity: 50, operation: 'INCREASE' };
      const updated = { ...mockProduct, stockQuantity: 150 };
      vi.mocked(api.put).mockResolvedValueOnce({ data: updated });

      const result = await productApi.updateProductStock(1, request);

      expect(api.put).toHaveBeenCalledWith('/api/products/1/stock', request);
      expect(result.stockQuantity).toBe(150);
    });

    it('재고를 감소시킨다', async () => {
      const request: UpdateProductStockRequest = { quantity: 30, operation: 'DECREASE' };
      const updated = { ...mockProduct, stockQuantity: 70 };
      vi.mocked(api.put).mockResolvedValueOnce({ data: updated });

      const result = await productApi.updateProductStock(1, request);

      expect(result.stockQuantity).toBe(70);
    });
  });

  // ─── 상태 변경 ───────────────────────────────────────────────
  describe('activateProduct', () => {
    it('상품을 활성화한다', async () => {
      const activated = { ...mockProduct, status: 'ACTIVE' as const, availableForSale: true };
      vi.mocked(api.post).mockResolvedValueOnce({ data: activated });

      const result = await productApi.activateProduct(1);

      expect(api.post).toHaveBeenCalledWith('/api/products/1/activate');
      expect(result.status).toBe('ACTIVE');
    });
  });

  describe('deactivateProduct', () => {
    it('상품을 비활성화한다', async () => {
      const deactivated = { ...mockProduct, status: 'INACTIVE' as const, availableForSale: false };
      vi.mocked(api.post).mockResolvedValueOnce({ data: deactivated });

      const result = await productApi.deactivateProduct(1);

      expect(api.post).toHaveBeenCalledWith('/api/products/1/deactivate');
      expect(result.status).toBe('INACTIVE');
      expect(result.availableForSale).toBe(false);
    });
  });

  describe('discontinueProduct', () => {
    it('상품을 단종 처리한다', async () => {
      const discontinued = { ...mockProduct, status: 'DISCONTINUED' as const, availableForSale: false };
      vi.mocked(api.post).mockResolvedValueOnce({ data: discontinued });

      const result = await productApi.discontinueProduct(1);

      expect(api.post).toHaveBeenCalledWith('/api/products/1/discontinue');
      expect(result.status).toBe('DISCONTINUED');
    });
  });
});