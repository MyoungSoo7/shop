import { describe, expect, it } from 'vitest';
import { isSupportedPaymentReturn, parsePaymentReturn } from '@/lib/paymentNavigation';

describe('paymentNavigation', () => {
  it('parses the same query contract for web and deep-link URLs', () => {
    const result = parsePaymentReturn('lemuel://payment/success?paymentKey=pk&amount=1000');
    expect(result.pathname).toBe('/payment/success');
    expect(result.params.get('paymentKey')).toBe('pk');
    expect(isSupportedPaymentReturn('lemuel://payment/success?paymentKey=pk')).toBe(true);
    expect(isSupportedPaymentReturn('lemuel://other')).toBe(false);
  });
});
