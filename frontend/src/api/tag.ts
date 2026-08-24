import api from './axios';
import {
  TagResponse,
  CreateTagRequest,
  UpdateTagRequest,
} from '@/types';

export const tagApi = {
  /**
   * 태그 생성
   */
  createTag: async (request: CreateTagRequest): Promise<TagResponse> => {
    const response = await api.post<TagResponse>('/api/tags', request);
    return response.data;
  },

  /**
   * 태그 조회
   */
  getTag: async (id: number): Promise<TagResponse> => {
    const response = await api.get<TagResponse>(`/api/tags/${id}`);
    return response.data;
  },

  /**
   * 전체 태그 목록 조회
   */
  getAllTags: async (): Promise<TagResponse[]> => {
    const response = await api.get<TagResponse[]>('/api/tags');
    return response.data;
  },

  /**
   * 상품별 태그 목록 조회
   */
  getTagsByProduct: async (productId: number): Promise<TagResponse[]> => {
    const response = await api.get<TagResponse[]>(`/api/tags/product/${productId}`);
    return response.data;
  },

  /**
   * 태그 수정
   */
  updateTag: async (id: number, request: UpdateTagRequest): Promise<TagResponse> => {
    const response = await api.put<TagResponse>(`/api/tags/${id}`, request);
    return response.data;
  },

  /**
   * 태그 삭제
   */
  deleteTag: async (id: number): Promise<void> => {
    await api.delete(`/api/tags/${id}`);
  },

  /**
   * 상품에 태그 추가
   */
  addTagToProduct: async (productId: number, tagId: number): Promise<void> => {
    await api.post(`/api/tags/product/${productId}/tag/${tagId}`);
  },

  /**
   * 상품에서 태그 제거
   */
  removeTagFromProduct: async (productId: number, tagId: number): Promise<void> => {
    await api.delete(`/api/tags/product/${productId}/tag/${tagId}`);
  },
};
