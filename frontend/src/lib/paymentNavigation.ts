export interface PaymentReturn {
  pathname: string;
  params: URLSearchParams;
}

export const parsePaymentReturn = (url: string): PaymentReturn => {
  const parsed = new URL(url, window.location.origin);
  const pathname = parsed.protocol !== 'http:' && parsed.protocol !== 'https:' && parsed.hostname
    ? `/${parsed.hostname}${parsed.pathname === '/' ? '' : parsed.pathname}`
    : parsed.pathname;
  return {
    pathname,
    params: parsed.searchParams,
  };
};

export const isSupportedPaymentReturn = (url: string): boolean => {
  const { pathname } = parsePaymentReturn(url);
  return pathname === '/payment/success' || pathname === '/payment/fail';
};
