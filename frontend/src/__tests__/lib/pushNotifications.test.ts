import { describe, expect, it } from 'vitest';
import { registerForPushNotifications } from '@/lib/pushNotifications';

describe('pushNotifications', () => {
  it('does not request browser notification permission implicitly', async () => {
    await expect(registerForPushNotifications()).resolves.toBeNull();
  });
});
