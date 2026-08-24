import { isNativePlatform } from './platform';

export const registerForPushNotifications = async (): Promise<string | null> => {
  if (!isNativePlatform()) return null;

  const { PushNotifications } = await import('@capacitor/push-notifications');
  let permission = await PushNotifications.checkPermissions();
  if (permission.receive === 'prompt') {
    permission = await PushNotifications.requestPermissions();
  }
  if (permission.receive !== 'granted') return null;

  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (value: string | null, error?: unknown) => {
      if (settled) return;
      settled = true;
      if (error) reject(error);
      else resolve(value);
    };

    void PushNotifications.addListener('registration', ({ value }) => finish(value));
    void PushNotifications.addListener('registrationError', (error) => finish(null, error));
    void PushNotifications.register().catch((error) => finish(null, error));
  });
};
