export const AUTH_STORAGE_KEYS = [
  'access_token',
  'user_email',
  'user_role',
  'login_timestamp',
] as const;

export type AuthStorageKey = (typeof AUTH_STORAGE_KEYS)[number];
export type AuthSession = Partial<Record<AuthStorageKey, string>>;

export interface AuthStorage {
  hydrate(): Promise<AuthSession>;
  get(key: AuthStorageKey): string | null;
  set(key: AuthStorageKey, value: string): Promise<void>;
  remove(key: AuthStorageKey): Promise<void>;
  clear(): Promise<void>;
}

const session: AuthSession = {};

const browserStorage: AuthStorage = {
  async hydrate() {
    for (const key of AUTH_STORAGE_KEYS) {
      const value = window.localStorage.getItem(key);
      if (value !== null) session[key] = value;
    }
    return { ...session };
  },
  get(key) {
    const value = window.localStorage.getItem(key);
    if (value === null) delete session[key];
    else session[key] = value;
    return value;
  },
  async set(key, value) {
    session[key] = value;
    window.localStorage.setItem(key, value);
  },
  async remove(key) {
    delete session[key];
    window.localStorage.removeItem(key);
  },
  async clear() {
    for (const key of AUTH_STORAGE_KEYS) {
      delete session[key];
      window.localStorage.removeItem(key);
    }
  },
};

let activeStorage: AuthStorage = browserStorage;

export const setAuthStorage = (storage: AuthStorage): void => {
  activeStorage = storage;
};

export const getAuthStorage = (): AuthStorage => activeStorage;

export const hydrateAuthStorage = async (): Promise<void> => {
  const hydrated = await getAuthStorage().hydrate();
  for (const key of AUTH_STORAGE_KEYS) {
    if (hydrated[key] === undefined) delete session[key];
  }
};
