import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.tsx'
import UpdatePrompt from './components/UpdatePrompt'
import InstallPrompt from './components/InstallPrompt'
import { registerServiceWorker } from './lib/serviceWorkerUpdate'
import { watchInstallAvailability } from './lib/installPrompt'
import { hydrateAuthStorage, setAuthStorage } from './lib/authStorage'
import { isNativePlatform } from './lib/platform'
import { initializeNativeLifecycle } from './lib/nativeLifecycle'
import './index.css'

// 렌더보다 **먼저** 리스너를 건다. beforeinstallprompt 는 페이지 로드 직후 발화할 수 있고,
// 리스너가 없는 동안 발화한 이벤트는 되찾을 방법이 없다(컴포넌트 마운트를 기다리면 늦다).
// 반대 방향의 경쟁(발화가 마운트보다 빠른 경우)은 onInstallAvailable 의 즉시 통지가 처리한다.
watchInstallAvailability()

const initializeAuthStorage = async () => {
  if (isNativePlatform()) {
    const { nativeStorage } = await import('./lib/nativeStorage')
    setAuthStorage(nativeStorage)
  }
  await hydrateAuthStorage()
  await initializeNativeLifecycle()
}

void initializeAuthStorage().finally(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      {/* 새 버전 알림·설치 유도 — 라우트와 무관한 앱 전역 사건이라 라우터 바깥에 둔다(컨텍스트 의존 없음). */}
      <UpdatePrompt />
      <InstallPrompt />
      <App />
    </React.StrictMode>,
  )
})

// 서비스워커 등록 — 예전에는 index.html 인라인 스크립트가 했으나, 새 버전 대기 상태를 화면(UpdatePrompt)에
// 전달해야 하므로 앱 코드로 옮겼다. 렌더를 막지 않도록 마운트 뒤에 부른다.
registerServiceWorker()
