import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ShippingAddressForm from '@/components/shipping/ShippingAddressForm';
import { emptyShippingAddress, isShippingAddressComplete } from '@/lib/shippingAddress';

describe('isShippingAddressComplete', () => {
  const filled = {
    recipientName: '홍길동',
    phone: '010-1234-5678',
    postalCode: '06236',
    address1: '서울시 강남구 테헤란로 1',
    address2: '',
    deliveryMemo: '',
  };

  it('필수 4항목이 차면 통과한다 (상세주소·요청사항은 선택)', () => {
    expect(isShippingAddressComplete(filled)).toBe(true);
  });

  it.each(['recipientName', 'phone', 'postalCode', 'address1'] as const)(
    '%s 가 비면 통과하지 않는다',
    (key) => {
      expect(isShippingAddressComplete({ ...filled, [key]: '' })).toBe(false);
    },
  );

  it('공백만 넣은 것은 채운 게 아니다 — 서버 스냅샷도 blank 를 거절한다', () => {
    expect(isShippingAddressComplete({ ...filled, recipientName: '   ' })).toBe(false);
  });

  it('빈 폼은 당연히 통과하지 않는다', () => {
    expect(isShippingAddressComplete(emptyShippingAddress())).toBe(false);
  });
});

describe('ShippingAddressForm', () => {
  it('입력한 필드만 바꾼 새 값을 올려 보낸다', async () => {
    const onChange = vi.fn();
    render(<ShippingAddressForm value={emptyShippingAddress()} onChange={onChange} />);

    await userEvent.type(screen.getByLabelText('받는 분'), '홍');

    expect(onChange).toHaveBeenCalledWith({ ...emptyShippingAddress(), recipientName: '홍' });
  });

  it('disabled 면 모든 입력이 잠긴다 (결제 진행 중 수정 방지)', () => {
    render(<ShippingAddressForm value={emptyShippingAddress()} onChange={vi.fn()} disabled />);

    expect(screen.getByLabelText('받는 분')).toBeDisabled();
    expect(screen.getByLabelText('주소')).toBeDisabled();
  });
});
