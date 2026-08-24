import React from 'react';

interface DateRangePickerProps {
  startDate: string;
  endDate: string;
  onStartDateChange: (date: string) => void;
  onEndDateChange: (date: string) => void;
  onQuickSelect?: (start: string, end: string) => void;
  error?: string;
}

const DateRangePicker: React.FC<DateRangePickerProps> = ({
  startDate,
  endDate,
  onStartDateChange,
  onEndDateChange,
  onQuickSelect,
  error,
}) => {
  const getToday = () => {
    return new Date().toISOString().split('T')[0];
  };

  const getDateBefore = (days: number) => {
    const date = new Date();
    date.setDate(date.getDate() - days);
    return date.toISOString().split('T')[0];
  };

  const getFirstDayOfMonth = () => {
    const date = new Date();
    date.setDate(1);
    return date.toISOString().split('T')[0];
  };

  const quickFilters = [
    { label: '최근 7일', getValue: () => ({ start: getDateBefore(7), end: getToday() }) },
    { label: '최근 30일', getValue: () => ({ start: getDateBefore(30), end: getToday() }) },
    { label: '이번 달', getValue: () => ({ start: getFirstDayOfMonth(), end: getToday() }) },
  ];

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-2">시작일</label>
          <input
            type="date"
            className={`w-full px-3 py-2 border rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-900 ${
              error ? 'border-red-300' : 'border-gray-300'
            }`}
            value={startDate}
            onChange={(e) => onStartDateChange(e.target.value)}
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 mb-2">종료일</label>
          <input
            type="date"
            className={`w-full px-3 py-2 border rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-900 ${
              error ? 'border-red-300' : 'border-gray-300'
            }`}
            value={endDate}
            onChange={(e) => onEndDateChange(e.target.value)}
          />
        </div>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {onQuickSelect && (
        <div className="flex flex-wrap gap-2">
          <span className="text-sm text-gray-600 mr-2">빠른 선택:</span>
          {quickFilters.map((filter) => (
            <button
              key={filter.label}
              type="button"
              onClick={() => {
                const { start, end } = filter.getValue();
                onQuickSelect(start, end);
              }}
              /* tap-target: 실측 높이 24px — 칩 모양을 키우지 않고 터치 영역만 44px 로 넓힌다. */
              className="tap-target px-3 py-1 text-xs font-medium text-blue-600 bg-blue-50 hover:bg-blue-100 rounded-md transition-colors"
            >
              {filter.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

export default DateRangePicker;
