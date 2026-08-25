import React from 'react';
import { ShippingAddressRequest } from '@/types';

/**
 * 결제 화면의 배송지 입력. 장바구니 결제와 단건 주문이 같은 폼을 쓴다.
 *
 * 이 값은 주문서에 그대로 굳는다(주문 행에 복사된다). 나중에 회원 주소록을 고쳐도 이미 만든
 * 주문의 배송지는 바뀌지 않아야 하기 때문이다. 도입 전에는 고객이 배송지를 낼 자리가 아예
 * 없어서 운영자가 관리자 화면에서 손으로 채워 넣었다.
 */
export interface ShippingAddressFormProps {
  value: ShippingAddressRequest;
  onChange: (next: ShippingAddressRequest) => void;
  disabled?: boolean;
}

// 값 검사(`isShippingAddressComplete`)와 빈 폼(`emptyShippingAddress`)은 `@/lib/shippingAddress`
// 에 있다. 컴포넌트 파일이 컴포넌트 외의 값을 내보내면 react-refresh 가 경고하고, 이 리포는
// `--max-warnings 0` 이라 그대로 빌드가 깨진다.

const field =
  'w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm text-gray-900 ' +
  'focus:ring-2 focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100';

const ShippingAddressForm: React.FC<ShippingAddressFormProps> = ({ value, onChange, disabled }) => {
  const set = (key: keyof ShippingAddressRequest) => (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>,
  ) => onChange({ ...value, [key]: e.target.value });

  return (
    <div className="space-y-3">
      <h3 className="font-bold text-gray-900">배송지</h3>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor="recipientName" className="block text-sm font-medium text-gray-700 mb-1.5">
            받는 분
          </label>
          <input id="recipientName" className={field} value={value.recipientName}
            onChange={set('recipientName')} disabled={disabled} autoComplete="name" />
        </div>
        <div>
          <label htmlFor="phone" className="block text-sm font-medium text-gray-700 mb-1.5">
            연락처
          </label>
          <input id="phone" className={field} value={value.phone} onChange={set('phone')}
            disabled={disabled} autoComplete="tel" placeholder="010-0000-0000" />
        </div>
      </div>

      <div>
        <label htmlFor="postalCode" className="block text-sm font-medium text-gray-700 mb-1.5">
          우편번호
        </label>
        <input id="postalCode" className={field} value={value.postalCode}
          onChange={set('postalCode')} disabled={disabled} autoComplete="postal-code" />
      </div>

      <div>
        <label htmlFor="address1" className="block text-sm font-medium text-gray-700 mb-1.5">
          주소
        </label>
        <input id="address1" className={field} value={value.address1} onChange={set('address1')}
          disabled={disabled} autoComplete="address-line1" />
      </div>

      <div>
        <label htmlFor="address2" className="block text-sm font-medium text-gray-700 mb-1.5">
          상세 주소 <span className="text-gray-400 font-normal">(선택)</span>
        </label>
        <input id="address2" className={field} value={value.address2 ?? ''} onChange={set('address2')}
          disabled={disabled} autoComplete="address-line2" />
      </div>

      <div>
        <label htmlFor="deliveryMemo" className="block text-sm font-medium text-gray-700 mb-1.5">
          배송 요청사항 <span className="text-gray-400 font-normal">(선택)</span>
        </label>
        <textarea id="deliveryMemo" rows={2} className={field} value={value.deliveryMemo ?? ''}
          onChange={set('deliveryMemo')} disabled={disabled} />
      </div>
    </div>
  );
};

export default ShippingAddressForm;
