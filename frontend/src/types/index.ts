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

/**
 * 주문 생성·쿠폰 미리보기에 공통으로 넘기는 라인.
 *
 * 금액이 없다는 점이 핵심이다 — 단가·할인·배송비는 전부 서버가 상품 마스터에서 확정한다.
 * 클라이언트는 "무엇을 몇 개" 까지만 말한다.
 */
export interface OrderLineRequest {
  productId: number;
  variantId?: number | null;
  quantity: number;
  /**
   * 자유입력(TEXT) 축코드 → 구매자가 적은 문구. 이 값은 SKU 를 만들지 않으므로 variantId 와
   * 별개로 실려 가고, 주문 라인의 옵션 스냅샷에 그대로 남는다. 쿠폰 미리보기에는 보내지 않는다
   * — 문구는 금액을 바꾸지 않는다.
   */
  optionTexts?: Record<string, string>;
}

/** 다건 주문의 라인 결과. allocatedDiscount 는 이 라인이 짊어진 할인 몫(부분취소 환불 단위). */
export interface MultiItemOrderLine {
  id: number;
  productId: number;
  variantId?: number | null;
  sku?: string | null;
  productName: string;
  unitPrice: number;
  quantity: number;
  lineAmount: number;
  allocatedDiscount: number;
  netAmount: number;
}

/**
 * 주문서에 굳는 배송지. 회원 주소록이 나중에 바뀌어도 이 주문의 값은 흔들리지 않는다
 * (그래서 배송 컨텍스트의 살아있는 주소와 별개로 주문 행에 복사해 둔다).
 */
export interface ShippingAddressRequest {
  recipientName: string;
  phone: string;
  postalCode: string;
  address1: string;
  address2?: string | null;
  deliveryMemo?: string | null;
}

/** POST /orders/multi 응답. amount = subtotal - discountAmount + shippingFee (서버가 확정). */
export interface MultiItemOrderResponse {
  id: number;
  userId: number;
  amount: number;
  status: string;
  subtotal: number;
  discountAmount: number;
  shippingFee: number;
  createdAt: string;
  /** 레거시 주문(배송지 도입 전)에는 없다. */
  shippingAddress?: ShippingAddressRequest | null;
  items: MultiItemOrderLine[];
}

/**
 * POST /orders/multi-destination 응답 — 한 번의 결제로 만들어진 주문 <b>여러 건</b>.
 *
 * 배송지 하나가 주문 하나다. 그래서 `orders` 의 각 원소는 단일 배송지 주문과 같은 모양이고,
 * `totalAmount` 는 서버가 그 주문들을 더한 값이다 — 화면이 다시 더하지 않는다. 원본(ssg-front)의
 * 같은 기능이 바로 그 자리에서 틀렸다: 장바구니 총액을 배송지 수만큼 곱해 청구하면서 재고는 한
 * 벌만 뺐다.
 */
export interface MultiDestinationOrderResponse {
  destinationGroupId: string;
  totalAmount: number;
  orders: MultiItemOrderResponse[];
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

/**
 * 쿠폰 미리보기에 넘기는 장바구니 한 줄.
 *
 * 주문 생성 라인과 <b>같은 타입</b>이다. 미리보기와 결제가 같은 입력을 받아야 두 금액이 갈라지지 않는다.
 */
export type CouponPreviewLine = OrderLineRequest;

/**
 * 장바구니 기준 쿠폰 계산 결과.
 *
 * `eligibleAmount` 는 할인이 실제로 걸린 금액(대상 라인들의 합)이다. 전체 적용 쿠폰이면
 * `subtotal` 과 같고, 상품·카테고리 전용 쿠폰이면 그보다 작다.
 */
export interface CouponPreviewResponse {
  valid: boolean;
  message: string;
  subtotal: number;
  discountAmount: number;
  eligibleAmount: number;
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
