import React, { useEffect, useState } from 'react';
import {
  onInstallAvailable,
  acceptInstall,
  snoozeInstall,
  type InstallMode,
} from '@/lib/installPrompt';

/**
 * 홈 화면 설치 배너.
 *
 * 이게 없으면 사용자는 브라우저 메뉴에서 "홈 화면에 추가"를 직접 찾아야 한다 — 설치형 PWA 를
 * 만들어 두고도 실제로 설치되지 않는 가장 흔한 이유다.
 *
 * iOS 는 설치를 코드로 실행할 방법이 없어(`beforeinstallprompt` 미발화) 안내 문구만 띄운다.
 * 두 경우의 버튼 구성이 달라지므로 `mode.kind` 로 분기한다.
 *
 * 위치는 하단 중앙 — 상단은 토스트, 그 아래는 업데이트 배너가 쓴다. 둘이 동시에 뜰 일은
 * 드물지만 겹치더라도 세로로 밀리도록 `UpdatePrompt` 보다 조금 더 위에 놓는다.
 */
const InstallPrompt: React.FC = () => {
  const [mode, setMode] = useState<InstallMode | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => onInstallAvailable(setMode), []);

  /**
   * 배너가 떠 있는 동안 화면 아래를 비운다(index.css 의 `body.pwa-banner-open`).
   * 고정 배너는 문서 흐름 밖이라 그냥 두면 하단 버튼 위에 얹혀 클릭을 막는다 — 실제로
   * 로그인 화면 데모 버튼이 눌리지 않아 e2e 6건이 실패했다. 표시 여부와 클래스를 한곳에서
   * 묶어, 배너가 사라질 때 여백도 반드시 함께 사라지게 한다.
   */
  useEffect(() => {
    if (!mode) return;
    document.body.classList.add('pwa-banner-open');
    return () => document.body.classList.remove('pwa-banner-open');
  }, [mode]);

  if (!mode) return null;

  return (
    <div className="fixed inset-x-0 bottom-0 z-40 flex justify-center px-4 pb-safe pointer-events-none">
      {/* 바깥은 클릭을 통과시키고 배너만 받는다 — 화면 하단을 막지 않기 위해서다. */}
      {/* `mb-20`(5rem) 로 띄웠더니 화면 중앙부까지 올라와 로그인 데모 버튼을 28px 덮었다
          (실측: 버튼 하단 534 / 배너 상단 506). 바닥에 붙여 침범 면적을 최소화한다.
          업데이트 배너와 동시에 뜨는 경우는 드물고, 겹치더라도 그건 미관 문제지만
          버튼을 못 누르는 것은 기능 결함이라 이쪽을 우선한다. */}
      <div className="pointer-events-auto mb-4 flex w-full max-w-md items-center gap-3 rounded-xl border border-blue-200 bg-white px-4 py-3 shadow-lg">
        <span className="text-lg leading-none">📲</span>

        {mode.kind === 'prompt' ? (
          <>
            <p className="flex-1 text-sm text-gray-900">
              앱으로 설치하기
              <span className="block text-xs text-gray-500">홈 화면에서 바로 열 수 있습니다.</span>
            </p>
            <button
              type="button"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                try {
                  await acceptInstall(mode);
                } finally {
                  setBusy(false);
                }
              }}
              className="tap-target shrink-0 rounded-lg bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-60"
            >
              {busy ? '설치 중…' : '설치'}
            </button>
          </>
        ) : (
          /* iOS: 실행할 수 없으니 경로만 알려 준다. 공유 아이콘 문구를 그대로 적어 찾기 쉽게 한다. */
          <p className="flex-1 text-sm text-gray-900">
            홈 화면에 추가하기
            <span className="block text-xs text-gray-500">
              하단 공유 버튼 → &lsquo;홈 화면에 추가&rsquo; 를 누르세요.
            </span>
          </p>
        )}

        <button
          type="button"
          onClick={() => snoozeInstall()}
          aria-label="나중에"
          className="tap-target shrink-0 rounded p-1 text-gray-400 hover:text-gray-600"
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

export default InstallPrompt;
