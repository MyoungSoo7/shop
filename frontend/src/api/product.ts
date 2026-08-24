import api from './axios';
import {
  ProductCreateRequest,
  ProductResponse,
  UpdateProductInfoRequest,
  UpdateProductPriceRequest,
  UpdateProductStockRequest,
  ProductStatus
} from '@/types';

export const productApi = {
  /**
   * 상품 생성
   * POST /api/products
   */
  createProduct: async (request: ProductCreateRequest): Promise<ProductResponse> => {
    const response = await api.post<ProductResponse>('/api/products', request);
    return response.data;
  },

  /**
   * 상품 조회
   * GET /api/products/{id}
   */
  getProduct: async (id: number): Promise<ProductResponse> => {
    const response = await api.get<ProductResponse>(`/api/products/${id}`);
    return response.data;
  },

  /**
   * 전체 상품 목록 조회
   * GET /api/products
   */
  getAllProducts: async (): Promise<ProductResponse[]> => {
    const response = await api.get<ProductResponse[]>('/api/products');
    return response.data;
  },

  /**
   * 상태별 상품 조회
   * GET /api/products/status/{status}
   */
  getProductsByStatus: async (status: ProductStatus): Promise<ProductResponse[]> => {
    const response = await api.get<ProductResponse[]>(`/api/products/status/${status}`);
    return response.data;
  },

  /**
   * 판매 가능한 상품 조회
   * GET /api/products/available
   */
  getAvailableProducts: async (): Promise<ProductResponse[]> => {
    const response = await api.get<ProductResponse[]>('/api/products/available');
    return response.data;
  },

  /**
   * 상품 정보 수정
   * PUT /api/products/{id}/info
   */
  updateProductInfo: async (id: number, request: UpdateProductInfoRequest): Promise<ProductResponse> => {
    const response = await api.put<ProductResponse>(`/api/products/${id}/info`, request);
    return response.data;
  },

  /**
   * 상품 가격 수정
   * PUT /api/products/{id}/price
   */
  updateProductPrice: async (id: number, request: UpdateProductPriceRequest): Promise<ProductResponse> => {
    const response = await api.put<ProductResponse>(`/api/products/${id}/price`, request);
    return response.data;
  },

  /**
   * 상품 재고 수정
   * PUT /api/products/{id}/stock
   */
  updateProductStock: async (id: number, request: UpdateProductStockRequest): Promise<ProductResponse> => {
    const response = await api.put<ProductResponse>(`/api/products/${id}/stock`, request);
    return response.data;
  },

  /**
   * 상품 활성화
   * POST /api/products/{id}/activate
   */
  activateProduct: async (id: number): Promise<ProductResponse> => {
    const response = await api.post<ProductResponse>(`/api/products/${id}/activate`);
    return response.data;
  },

  /**
   * 상품 비활성화
   * POST /api/products/{id}/deactivate
   */
  deactivateProduct: async (id: number): Promise<ProductResponse> => {
    const response = await api.post<ProductResponse>(`/api/products/${id}/deactivate`);
    return response.data;
  },

  /**
   * 상품 단종
   * POST /api/products/{id}/discontinue
   */
  discontinueProduct: async (id: number): Promise<ProductResponse> => {
    const response = await api.post<ProductResponse>(`/api/products/${id}/discontinue`);
    return response.data;
  },
};
