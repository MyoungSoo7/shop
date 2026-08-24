import { beforeEach, describe, expect, it } from 'vitest';
import { getAuthStorage, hydrateAuthStorage } from '@/lib/authStorage';

describe('authStorage', () => {
  beforeEach(async () => {
    localStorage.clear();
    await getAuthStorage().clear();
  });

  it('hydrates the in-memory session from browser storage', async () => {
    localStorage.setItem('access_token', 'jwt');
    localStorage.setItem('user_role', 'USER');

    await hydrateAuthStorage();

    expect(getAuthStorage().get('access_token')).toBe('jwt');
    expect(getAuthStorage().get('user_role')).toBe('USER');
  });

  it('persists writes and removes through the storage contract', async () => {
    await getAuthStorage().set('access_token', 'jwt');
    expect(localStorage.getItem('access_token')).toBe('jwt');

    await getAuthStorage().remove('access_token');
    expect(getAuthStorage().get('access_token')).toBeNull();
    expect(localStorage.getItem('access_token')).toBeNull();
  });
});
