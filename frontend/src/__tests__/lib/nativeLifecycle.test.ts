import { describe, expect, it } from 'vitest';
import { initializeNativeLifecycle, normalizeAppUrl } from '@/lib/nativeLifecycle';

describe('nativeLifecycle', () => {
  it('normalizes web and custom-scheme URLs to router paths', () => {
    expect(normalizeAppUrl('https://lemuel.co.kr/payment/success?paymentKey=x')).toBe(
      '/payment/success?paymentKey=x',
    );
    expect(normalizeAppUrl('lemuel://reset?token=x')).toBe('/reset?token=x');
  });

  it('does nothing in the browser runtime', async () => {
    await expect(initializeNativeLifecycle()).resolves.toBeUndefined();
  });
});
