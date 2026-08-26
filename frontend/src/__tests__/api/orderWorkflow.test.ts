import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  orderWorkflowApi,
  canRequestCancellation,
  canRequestRefund,
  canRequestExchange,
  isAwaitingApproval,
  hasOpenRequest,
  AWAITING_APPROVAL_STATUSES,
  ORDER_STATUS_LABEL,
  type OrderStatusValue,
} from '@/api/orderWorkflow';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { post: vi.fn() },
}));

const order = { id: 42, userId: 7, productId: 1, amount: 10000, status: 'REFUND_REQUESTED',
  createdAt: '2026-08-09T10:00:00', updatedAt: '2026-08-09T10:00:00' };

describe('orderWorkflowApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.post).mockResolvedValue({ data: order });
  });

  it('취소 신청은 사용자 경로로 사유와 함께 간다', async () => {
    await orderWorkflowApi.requestCancellation(42, '단순 변심');

    expect(api.post).toHaveBeenCalledWith('/orders/42/cancellation-request', { reason: '단순 변심' });
  });

  it('환불 신청은 사용자 경로로 사유와 함께 간다', async () => {
    await orderWorkflowApi.requestRefund(42, '상품 파손');

    expect(api.post).toHaveBeenCalledWith('/orders/42/refund-request', { reason: '상품 파손' });
  });

  /**
   * 되돌아갈 상태를 클라이언트가 고르지 않는다는 것이 이 경로의 핵심이다. 본문에 목표 상태가
   * 실리면 화면이 전이표를 다시 쓰는 셈이 되고, 신청한 적 없는 상태로도 갈 수 있게 된다.
   */
  it('철회는 사유만 싣고 돌아갈 상태를 지정하지 않는다', async () => {
    await orderWorkflowApi.withdrawRequest(42, '다시 받기로 했습니다');

    expect(api.post).toHaveBeenCalledWith('/orders/42/request-withdraw', {
      reason: '다시 받기로 했습니다',
    });
  });

  it('사유 없이 철회하면 reason 은 null 로 간다', async () => {
    await orderWorkflowApi.withdrawRequest(42);

    expect(api.post).toHaveBeenCalledWith('/orders/42/request-withdraw', { reason: null });
  });

  /**
   * 신청과 승인이 다른 경로라는 것이 곧 권한 모델이다 — /orders/admin/** 만 ADMIN·MANAGER 게이트다.
   * 승인이 사용자 경로로 새면 사용자가 스스로 환불을 완결시킬 수 있다.
   */
  it('승인은 사용자 경로가 아니라 /orders/admin 경로로 간다', async () => {
    await orderWorkflowApi.approveCancellation(42, '재고 부족');
    expect(api.post).toHaveBeenCalledWith('/orders/admin/42/cancellation-approve', { reason: '재고 부족' });

    await orderWorkflowApi.approveRefund(42, '검수 완료');
    expect(api.post).toHaveBeenCalledWith('/orders/admin/42/refund-approve', { reason: '검수 완료' });
  });
});

describe('신청 가능 여부 — 서버 전이표의 사본', () => {
  it('취소 신청은 결제 전(CREATED)·결제 완료(PAID) 에서만 가능하다', () => {
    expect(canRequestCancellation('CREATED')).toBe(true);
    expect(canRequestCancellation('PAID')).toBe(true);
    expect(canRequestCancellation('IN_TRANSIT')).toBe(false);
    expect(canRequestCancellation('CANCELED')).toBe(false);
  });

  it('환불 신청은 결제 이후 배송 단계 전반에서 가능하다', () => {
    ['PAID', 'SHIPPING_PENDING', 'IN_TRANSIT', 'DELIVERED', 'CANCELLATION_APPROVED'].forEach((s) =>
      expect(canRequestRefund(s)).toBe(true)
    );
  });

  it('결제 전 주문은 환불 신청 대상이 아니다 — 낼 돈을 안 냈다', () => {
    expect(canRequestRefund('CREATED')).toBe(false);
  });

  it('종단 상태에서는 아무 신청도 열리지 않는다', () => {
    ['CANCELED', 'REFUNDED'].forEach((s) => {
      expect(canRequestCancellation(s)).toBe(false);
      expect(canRequestRefund(s)).toBe(false);
    });
  });

  it('교환 신청은 배송 단계에서 가능하되 취소 승인된 주문은 제외다', () => {
    ['PAID', 'SHIPPING_PENDING', 'IN_TRANSIT', 'DELIVERED'].forEach((s) =>
      expect(canRequestExchange(s)).toBe(true)
    );
    // 취소가 승인된 주문은 돈이 돌아가는 중이라 보낼 물건이 없다.
    expect(canRequestExchange('CANCELLATION_APPROVED')).toBe(false);
    expect(canRequestExchange('CREATED')).toBe(false);
  });

  it('종단 상태에서는 아무 신청도 열리지 않는다 (교환 포함)', () => {
    ['CANCELED', 'REFUNDED'].forEach((s) => expect(canRequestExchange(s)).toBe(false));
  });

  it('승인 대기는 두 신청 상태뿐이다', () => {
    expect(isAwaitingApproval('CANCELLATION_REQUESTED')).toBe(true);
    expect(isAwaitingApproval('REFUND_REQUESTED')).toBe(true);
    expect(isAwaitingApproval('PAID')).toBe(false);
    expect(isAwaitingApproval('CANCELLATION_APPROVED')).toBe(false);
  });

  /**
   * 두 상수가 갈리는 지점. 주문 승인 큐에는 취소 승인·환불 승인 버튼밖에 없어서, 교환을 거기
   * 섞으면 <b>맞는 버튼이 하나도 없는 행</b>이 서고 운영자가 그걸 환불로 승인해 버린다.
   * 반면 철회는 셋 다 가능하다.
   */
  it('교환은 승인 큐에 들어가지 않지만 철회는 열린다', () => {
    expect(isAwaitingApproval('EXCHANGE_REQUESTED')).toBe(false);
    expect(AWAITING_APPROVAL_STATUSES).not.toContain('EXCHANGE_REQUESTED');

    expect(hasOpenRequest('EXCHANGE_REQUESTED')).toBe(true);
    expect(hasOpenRequest('CANCELLATION_REQUESTED')).toBe(true);
    expect(hasOpenRequest('REFUND_REQUESTED')).toBe(true);
    expect(hasOpenRequest('PAID')).toBe(false);
  });
});

describe('ORDER_STATUS_LABEL', () => {
  it('서버 enum 전체에 한글 라벨이 있다 — 원시 문자열이 화면에 새지 않는다', () => {
    const all: OrderStatusValue[] = [
      'CREATED', 'PAID', 'SHIPPING_PENDING', 'IN_TRANSIT', 'DELIVERED',
      'CANCELLATION_REQUESTED', 'CANCELLATION_APPROVED', 'REFUND_REQUESTED',
      'REFUND_COMPLETED', 'EXCHANGE_REQUESTED', 'CANCELED', 'REFUNDED',
    ];
    all.forEach((s) => {
      expect(ORDER_STATUS_LABEL[s]).toBeTruthy();
      expect(ORDER_STATUS_LABEL[s]).not.toBe(s);
    });
  });
});
