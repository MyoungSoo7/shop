import React from 'react';

interface StatusBadgeProps {
  status: string;
  type?: 'settlement' | 'payment' | 'order';
}

const StatusBadge: React.FC<StatusBadgeProps> = ({ status, type = 'settlement' }) => {
  const getStatusConfig = (status: string, type: string) => {
    if (type === 'settlement') {
      const configs: Record<string, { label: string; className: string }> = {
        REQUESTED: { label: '요청됨', className: 'bg-yellow-100 text-yellow-800 border-yellow-200' },
        PROCESSING: { label: '처리중', className: 'bg-blue-100 text-blue-800 border-blue-200' },
        DONE: { label: '완료', className: 'bg-green-100 text-green-800 border-green-200' },
        FAILED: { label: '실패', className: 'bg-red-100 text-red-800 border-red-200' },
        CANCELED: { label: '취소됨', className: 'bg-gray-100 text-gray-800 border-gray-200' },
      };
      return configs[status] || { label: status, className: 'bg-gray-100 text-gray-800 border-gray-200' };
    }

    if (type === 'payment') {
      const configs: Record<string, { label: string; className: string }> = {
        PENDING: { label: '대기', className: 'bg-yellow-100 text-yellow-800 border-yellow-200' },
        PAID: { label: '결제완료', className: 'bg-green-100 text-green-800 border-green-200' },
        CAPTURED: { label: '승인완료', className: 'bg-blue-100 text-blue-800 border-blue-200' },
        FAILED: { label: '실패', className: 'bg-red-100 text-red-800 border-red-200' },
        CANCELED: { label: '취소', className: 'bg-gray-100 text-gray-800 border-gray-200' },
        PARTIAL_REFUNDED: { label: '부분환불', className: 'bg-orange-100 text-orange-800 border-orange-200' },
        REFUNDED: { label: '환불완료', className: 'bg-red-100 text-red-800 border-red-200' },
      };
      return configs[status] || { label: status, className: 'bg-gray-100 text-gray-800 border-gray-200' };
    }

    if (type === 'order') {
      const configs: Record<string, { label: string; className: string }> = {
        PENDING: { label: '대기', className: 'bg-yellow-100 text-yellow-800 border-yellow-200' },
        CONFIRMED: { label: '확정', className: 'bg-blue-100 text-blue-800 border-blue-200' },
        COMPLETED: { label: '완료', className: 'bg-green-100 text-green-800 border-green-200' },
        CANCELED: { label: '취소', className: 'bg-red-100 text-red-800 border-red-200' },
      };
      return configs[status] || { label: status, className: 'bg-gray-100 text-gray-800 border-gray-200' };
    }

    return { label: status, className: 'bg-gray-100 text-gray-800 border-gray-200' };
  };

  const config = getStatusConfig(status, type);

  return (
    <span
      className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${config.className}`}
    >
      {config.label}
    </span>
  );
};

export default StatusBadge;
