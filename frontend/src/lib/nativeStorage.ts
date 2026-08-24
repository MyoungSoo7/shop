import { SecureStorage } from '@aparajita/capacitor-secure-storage';
import { AUTH_STORAGE_KEYS, type AuthSession, type AuthStorage, type AuthStorageKey } from './authStorage';

const nativeSession: AuthSession = {};

export const nativeStorage: AuthStorage = {
  async hydrate() {
    for (const key of AUTH_STORAGE_KEYS) {
      const value = await SecureStorage.getItem(key);
      if (value === null) delete nativeSession[key];
      else nativeSession[key] = value;
    }
    return { ...nativeSession };
  },
  get(key: AuthStorageKey) {
    return nativeSession[key] ?? null;
  },
  async set(key, value) {
    nativeSession[key] = value;
    await SecureStorage.setItem(key, value);
  },
  async remove(key) {
    delete nativeSession[key];
    await SecureStorage.removeItem(key);
  },
  async clear() {
    for (const key of AUTH_STORAGE_KEYS) delete nativeSession[key];
    await SecureStorage.clear();
  },
};
