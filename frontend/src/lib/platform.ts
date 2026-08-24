import { Capacitor } from '@capacitor/core';

export const isNativePlatform = (): boolean => Capacitor.isNativePlatform();
export const isWebPlatform = (): boolean => !isNativePlatform();
