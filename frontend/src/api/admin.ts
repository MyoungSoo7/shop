import api from './axios';
import { OrderResponse } from '@/types';

export interface AdminUserResponse {
  id: number;
  email: string;
  role: string;
  createdAt: string;
}

export const adminApi = {
  /** GET /orders/admin/all */
  getAllOrders: async (): Promise<OrderResponse[]> => {
    const response = await api.get<OrderResponse[]>('/orders/admin/all');
    return response.data;
  },

  /** GET /users/admin/all */
  getAllUsers: async (): Promise<AdminUserResponse[]> => {
    const response = await api.get<AdminUserResponse[]>('/users/admin/all');
    return response.data;
  },
};