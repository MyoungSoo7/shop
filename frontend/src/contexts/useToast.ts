import { createContext, useContext } from 'react';
import { ToastType } from '@/components/Toast';

/**
 * Toast 컨텍스트와 소비 훅 — 컴포넌트(ToastProvider)와 <b>파일을 분리</b>한다.
 *
 * <p>컴포넌트 파일이 컴포넌트 외의 값을 함께 export 하면 Vite 의 Fast Refresh 가 그 모듈 갱신 시
 * 상태를 보존하지 못한다(react-refresh/only-export-components). 훅·컨텍스트는 컴포넌트가 아니므로
 * 이 파일에 둔다.
 */
export interface ToastContextType {
  showToast: (message: string, type?: ToastType) => void;
}

export const ToastContext = createContext<ToastContextType | undefined>(undefined);

export const useToast = () => {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within ToastProvider');
  }
  return context;
};
