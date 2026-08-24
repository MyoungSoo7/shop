import React, { useEffect, useState } from 'react';
import { onUpdateReady, applyUpdate } from '@/lib/serviceWorkerUpdate';

/**
 * 새 버전 알림 배너.
 *
 * 서비스워커가 스스로 교체하지 않게 바꾼(`sw.js`) 뒤, 교체 시점을 사용자에게 넘기는 창구다.
 * 자동 새로고침을 하지 않는 이유는 정산 화면에서 입력 중이거나 조회 결과를 보는 중일 수 있어서다 —
 * 언제 화면이 바뀔지는 사용자가 정한다.
 *
 * 하단 중앙에 띄운다(상단은 토스트 자리). 홈 인디케이터에 물리지 않게 `pb-safe` 를 얹는다.
 */
const UpdatePrompt: React.FC = () => {
  const [waiting, setWaiting] = useState<ServiceWorker | null>(null);
  const [applying, setApplying] = useState(false);

  useEffect(() => onUpdateReady(setWaiting), []);

  if (!waiting) return null;

  return (
    <div className="fixed inset-x-0 bottom-0 z-50 flex justify-center px-4 pb-safe pointer-events-none">
      {/* 바깥은 클릭을 통과시키고(pointer-events-none) 배너만 받는다 — 화면 하단을 막지 않기 위해서다. */}
      <div className="pointer-events-auto mb-4 flex w-full max-w-md items-center gap-3 rounded-xl border border-gray-700 bg-gray-900 px-4 py-3 shadow-lg">
        <span className="text-lg leading-none">🔄</span>
        <p className="flex-1 text-sm text-white">
          새 버전이 준비됐습니다.
          <span className="block text-xs text-gray-400">지금 갱신하면 화면을 다시 불러옵니다.</span>
        </p>
        <button
          type="button"
          onClick={() => {
            setApplying(true);
            applyUpdate(waiting);
          }}
          disabled={applying}
          className="tap-target shrink-0 rounded-lg bg-white px-3 py-2 text-sm font-semibold text-gray-900 hover:bg-gray-200 disabled:opacity-60"
        >
          {applying ? '갱신 중…' : '지금 갱신'}
        </button>
        <button
          type="button"
          onClick={() => setWaiting(null)}
          aria-label="나중에"
          className="tap-target shrink-0 rounded p-1 text-gray-400 hover:text-white"
        >
          <svg className="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
              clipRule="evenodd"
            />
          </svg>
        </button>
      </div>
    </div>
  );
};

export default UpdatePrompt;
