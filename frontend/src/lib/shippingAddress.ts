import { ShippingAddressRequest } from '@/types';

/**
 * 서버(`ShippingAddressSnapshot`)가 요구하는 필수 4항목. 비거나 공백뿐이면 400 이라
 * 화면에서 먼저 막는다.
 */
export const isShippingAddressComplete = (a: ShippingAddressRequest): boolean =>
  a.recipientName.trim() !== '' &&
  a.phone.trim() !== '' &&
  a.postalCode.trim() !== '' &&
  a.address1.trim() !== '';

export const emptyShippingAddress = (): ShippingAddressRequest => ({
  recipientName: '',
  phone: '',
  postalCode: '',
  address1: '',
  address2: '',
  deliveryMemo: '',
});
