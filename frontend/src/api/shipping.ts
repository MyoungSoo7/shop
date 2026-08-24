import api from './axios';

/**
 * 배송 — order-service {@code ShippingController} (/orders/{orderId}/shipment).
 *
 * <p>상태머신은 서버 도메인이 강제한다: PENDING/READY → SHIPPED → IN_TRANSIT → DELIVERED → RETURNED.
 * 프론트는 <b>가능한 다음 전이만 버튼으로 노출</b>할 뿐, 판정의 정본은 서버다
 * (비정상 전이는 400 + INVALID_STATE 로 되돌아온다).
 *
 * <p>응답이 {@code { shipment: {...} }} 로 한 겹 감싸여 오므로 여기서 벗겨 돌려준다 —
 * 껍데기를 화면까지 들고 가면 모든 사용처가 {@code res.shipment.status} 를 쓰게 된다.
 */

export type ShippingStatus =
  | 'PENDING'
  | 'READY'
  | 'SHIPPED'
  | 'IN_TRANSIT'
  | 'DELIVERED'
  | 'RETURNED';

export interface Shipment {
  id: number;
  orderId: number;
  status: ShippingStatus;
  recipientName: string;
  phone: string;
  postalCode: string;
  address1: string;
  address2: string | null;
  deliveryMemo: string | null;
  carrier: string | null;
  trackingNumber: string | null;
  shippedAt: string | null;
  deliveredAt: string | null;
}

export interface ShippingAddressRequest {
  recipientName: string;
  phone: string;
  postalCode: string;
  address1: string;
  address2?: string;
  deliveryMemo?: string;
}

export interface ShipRequest {
  carrier: string;
  trackingNumber: string;
}

/** 서버 응답 껍데기 — 이 모듈 밖으로 새지 않는다. */
interface ShipmentEnvelope {
  shipment: Shipment;
}

const base = (orderId: number) => `/orders/${orderId}/shipment`;

/** 사용자에게 보여줄 상태 라벨. 서버 enum 을 그대로 노출하지 않는다. */
export const SHIPPING_STATUS_LABEL: Record<ShippingStatus, string> = {
  PENDING: '배송 준비 전',
  READY: '출고 대기',
  SHIPPED: '출고 완료',
  IN_TRANSIT: '배송 중',
  DELIVERED: '배송 완료',
  RETURNED: '반품됨',
};

/**
 * 현재 상태에서 운영자가 실행할 수 있는 다음 전이.
 * 서버 {@code Shipment} 도메인의 전이 규칙을 그대로 옮긴 것이며, 어긋나면 서버가 400 으로 막는다.
 */
export const nextShippingActions = (
  status: ShippingStatus
): Array<'ship' | 'in-transit' | 'delivered' | 'returned'> => {
  switch (status) {
    case 'PENDING':
    case 'READY':
      return ['ship'];
    case 'SHIPPED':
      return ['in-transit', 'delivered'];
    case 'IN_TRANSIT':
      return ['delivered'];
    case 'DELIVERED':
      return ['returned'];
    case 'RETURNED':
    default:
      return [];
  }
};

/** 송장 일괄 등록 행별 결과 — 실패는 사유를 담는다. */
export interface BulkTrackingLine {
  orderId: number | null;
  carrier: string | null;
  trackingNumber: string | null;
  applied: boolean;
  reason: string;
}

/** `dryRun` 이 true 면 아무것도 바뀌지 않은 미리보기다 — `applied` 는 "반영될 수 있는 행 수"다. */
export interface BulkTrackingResult {
  applied: number;
  failed: number;
  dryRun: boolean;
  lines: BulkTrackingLine[];
}

export const shippingApi = {
  /** GET — 배송이 없으면 404 다(주문은 있으나 배송 생성 전). */
  get: async (orderId: number): Promise<Shipment> => {
    const response = await api.get<ShipmentEnvelope>(base(orderId));
    return response.data.shipment;
  },

  /** POST — 주문에 배송 생성(PENDING). */
  create: async (orderId: number, address: ShippingAddressRequest): Promise<Shipment> => {
    const response = await api.post<ShipmentEnvelope>(base(orderId), address);
    return response.data.shipment;
  },

  /** PATCH /address — PENDING 에서만 가능하다. */
  changeAddress: async (orderId: number, address: ShippingAddressRequest): Promise<Shipment> => {
    const response = await api.patch<ShipmentEnvelope>(`${base(orderId)}/address`, address);
    return response.data.shipment;
  },

  /** POST /ship — 운송장 발급(PENDING·READY → SHIPPED). */
  ship: async (orderId: number, request: ShipRequest): Promise<Shipment> => {
    const response = await api.post<ShipmentEnvelope>(`${base(orderId)}/ship`, request);
    return response.data.shipment;
  },

  /** POST /in-transit — 택배사 첫 스캔(SHIPPED → IN_TRANSIT). */
  markInTransit: async (orderId: number): Promise<Shipment> => {
    const response = await api.post<ShipmentEnvelope>(`${base(orderId)}/in-transit`);
    return response.data.shipment;
  },

  /** POST /delivered — SHIPPED·IN_TRANSIT → DELIVERED. */
  markDelivered: async (orderId: number): Promise<Shipment> => {
    const response = await api.post<ShipmentEnvelope>(`${base(orderId)}/delivered`);
    return response.data.shipment;
  },

  /** POST /returned — DELIVERED → RETURNED. 서버가 재고를 되돌린다. */
  markReturned: async (orderId: number): Promise<Shipment> => {
    const response = await api.post<ShipmentEnvelope>(`${base(orderId)}/returned`);
    return response.data.shipment;
  },

  /**
   * POST /admin/shipments/tracking-upload — 송장 CSV 일괄 등록.
   *
   * <p>**`dryRun` 기본값이 true 인 것은 서버 규약이다.** 수백 행이 한 번에 출고 처리되는 작업이라,
   * 파라미터를 빠뜨린 호출이 곧바로 실행이 되어선 안 된다. 여기서도 기본값을 그대로 두어
   * 호출부가 실행을 <b>명시</b>하게 한다 — 기본값을 false 로 뒤집으면 서버가 세운 안전장치를
   * 클라이언트가 무력화하는 셈이다.
   */
  uploadTracking: async (file: File, dryRun = true): Promise<BulkTrackingResult> => {
    const form = new FormData();
    form.append('file', file);
    const response = await api.post<BulkTrackingResult>('/admin/shipments/tracking-upload', form, {
      params: { dryRun },
    });
    return response.data;
  },
};
