import React, { useState, useCallback, ReactNode, useEffect } from 'react';
import Toast, { ToastType } from '@/components/Toast';
import { setGlobalToast } from '@/api/axios';
import { ToastContext } from '@/contexts/useToast';

interface ToastMessage {
  id: string;
  message: string;
  type: ToastType;
}


interface ToastProviderProps {
  children: ReactNode;
}

export const ToastProvider: React.FC<ToastProviderProps> = ({ children }) => {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const showToast = useCallback((message: string, type: ToastType = 'info') => {
    const id = Math.random().toString(36).substring(7);
    setToasts((prev) => [...prev, { id, message, type }]);
  }, []);

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  }, []);

  // axios interceptor에 전역 Toast 함수 등록
  useEffect(() => {
    setGlobalToast(showToast);
  }, [showToast]);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="fixed top-4 right-4 z-50 space-y-2">
        {toasts.map((toast, index) => (
          <div key={toast.id} style={{ marginTop: index > 0 ? '8px' : '0' }}>
            <Toast message={toast.message} type={toast.type} onClose={() => removeToast(toast.id)} />
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
};
