// Auth Types
export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  role: 'USER' | 'ADMIN' | 'MANAGER';
}

export interface UserResponse {
  id: number;
  email: string;
  createdAt: string;
  updatedAt: string;
}

export interface LoginResponse {
  token: string;
  email: string;
  role: string;
}

// Settlement Search Types
export interface SettlementSearchRequest {
  ordererName?: string;
  productName?: string;
  isRefunded?: boolean;
  status?: 'REQUESTED' | 'PROCESSING' | 'DONE' | 'FAILED' | 'CANCELED';
  startDate?: string;
  endDate?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDirection?: 'ASC' | 'DESC';
}

export interface SettlementDetail {
  id: number;
  paymentId: number;
  orderId: number;
  paymentAmount: number;
  commission: number;
  netAmount: number;
  status: string;
  settlementDate: string;
  confirmedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface SettlementSearchItem {
  settlementId: number;
  orderId: number;
  paymentId: number;
  ordererName: string;
  productName: string;
  amount: number;
  refundedAmount: number;
  finalAmount: number;
  status: string;
  isRefunded: boolean;
  settlementDate: string;
  createdAt: string;
}

export interface SettlementAggregations {
  totalAmount: number;
  totalRefundedAmount: number;
  totalFinalAmount: number;
  statusCounts: Record<string, number>;
}

export interface SettlementSearchResponse {
  settlements: SettlementSearchItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
  aggregations: SettlementAggregations;
}

// Order Types
export interface OrderCreateRequest {
  userId: number;
  productId: number;
  amount: number;
}

export interface OrderResponse {
  id: number;
  userId: number;
  productId: number;
  amount: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

// Payment Types
export interface PaymentRequest {
  orderId: number;
  paymentMethod: string;
}

export interface TossConfirmRequest {
  dbOrderId: number;
  paymentKey: string;
  tossOrderId: string;
  amount: number;
}

export interface TossCartConfirmRequest {
  orderIds: number[];
  paymentKey: string;
  tossOrderId: string;
  totalAmount: number;
}

export interface PaymentResponse {
  id: number;
  orderId: number;
  amount: number;
  refundedAmount: number;
  paymentMethod: string;
  status: string;
  pgTransactionId?: string;
  capturedAt?: string;
  createdAt: string;
  updatedAt: string;
}

// Refund Types
export interface RefundRequest {
  amount: number;
  reason?: string;
}

export interface RefundResponse {
  id: number;
  paymentId: number;
  amount: number;
  reason?: string;
  status: string;
  idempotencyKey: string;
  createdAt: string;
  payment: PaymentResponse;
}

// Product Types
export type ProductStatus = 'ACTIVE' | 'INACTIVE' | 'OUT_OF_STOCK' | 'DISCONTINUED';
export type StockOperation = 'INCREASE' | 'DECREASE';

export interface ProductCreateRequest {
  name: string;
  description?: string;
  price: number;
  stockQuantity: number;
}

export interface ProductResponse {
  id: number;
  name: string;
  description?: string;
  price: number;
  stockQuantity: number;
  status: ProductStatus;
  availableForSale: boolean;
  createdAt: string;
  updatedAt: string;
  primaryImageUrl?: string;
}

export interface UpdateProductInfoRequest {
  name?: string;
  description?: string;
}

export interface UpdateProductPriceRequest {
  newPrice: number;
}

export interface UpdateProductStockRequest {
  quantity: number;
  operation: StockOperation;
}

// Product Image Types
export interface ProductImageResponse {
  id: number;
  productId: number;
  originalFileName: string;
  storedFileName: string;
  filePath: string;
  url: string;
  contentType: string;
  sizeBytes: number;
  width?: number;
  height?: number;
  checksum?: string;
  isPrimary: boolean;
  orderIndex: number;
  createdAt: string;
  updatedAt: string;
}

// Review Types
export interface ReviewCreateRequest {
  productId: number;
  userId: number;
  rating: number; // 1-5
  content?: string;
}

export interface ReviewUpdateRequest {
  userId: number;
  rating: number;
  content?: string;
}

export interface ReviewResponse {
  id: number;
  productId: number;
  userId: number;
  rating: number;
  content?: string;
  createdAt: string;
  updatedAt: string;
}

// Category Types
export interface CategoryResponse {
  id: number;
  name: string;
  description?: string;
  parentId?: number;
  displayOrder: number;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCategoryRequest {
  name: string;
  description?: string;
  parentId?: number;
  displayOrder?: number;
}

export interface UpdateCategoryRequest {
  name?: string;
  description?: string;
  displayOrder?: number;
}

// Tag Types
export interface TagResponse {
  id: number;
  name: string;
  color: string;
  createdAt: string;
}

export interface CreateTagRequest {
  name: string;
  color: string;
}

export interface UpdateTagRequest {
  name?: string;
  color?: string;
}

// Coupon Types
export type CouponType = 'FIXED' | 'PERCENTAGE';

export interface CouponResponse {
  id: number;
  code: string;
  type: CouponType;
  discountValue: number;
  minOrderAmount: number;
  maxUses: number;
  usedCount: number;
  expiresAt?: string;
  isActive: boolean;
  createdAt: string;
}

export interface CouponValidateResponse {
  valid: boolean;
  message: string;
  discountAmount: number;
  finalAmount: number;
}

export interface CouponCreateRequest {
  code: string;
  type: CouponType;
  discountValue: number;
  minOrderAmount: number;
  maxUses: number;
  expiresAt?: string;
}
