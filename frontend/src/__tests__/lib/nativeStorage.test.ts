import { beforeEach, describe, expect, it, vi } from 'vitest';

const values = new Map<string, string>();

vi.mock('@aparajita/capacitor-secure-storage', () => ({
  SecureStorage: {
    getItem: vi.fn(async (key: string) => values.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => void values.set(key, value)),
    removeItem: vi.fn(async (key: string) => void values.delete(key)),
    clear: vi.fn(async () => void values.clear()),
  },
}));

import { nativeStorage } from '@/lib/nativeStorage';

describe('nativeStorage', () => {
  beforeEach(async () => {
    values.clear();
    await nativeStorage.clear();
  });

  it('hydrates and persists session values through secure storage', async () => {
    values.set('access_token', 'native-jwt');
    await nativeStorage.hydrate();
    expect(nativeStorage.get('access_token')).toBe('native-jwt');

    await nativeStorage.set('user_role', 'USER');
    expect(values.get('user_role')).toBe('USER');
  });
});
