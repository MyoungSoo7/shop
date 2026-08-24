import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    rollupOptions: {
      output: {
        // vite 8 은 번들러가 Rollup → Rolldown 으로 바뀌었다. Rolldown 은 `manualChunks` 의
        // 객체형(이름 → 모듈 배열)을 받지 않고 함수로 호출하려 들어 "manualChunks is not a
        // function" 으로 빌드가 죽는다. 같은 의도를 Rolldown 의 `advancedChunks` 로 옮긴다.
        //
        // 모듈 id(절대경로)에 대한 test 라서 node_modules 경계를 명시해야 앱 코드의 동명
        // 디렉터리가 딸려 들어가지 않는다. 구분자는 OS 별로 다르므로 [\\/] 로 둘 다 받는다.
        // react-router-dom 은 react-router 를 재수출하므로 둘을 같은 청크에 묶는다 —
        // 나뉘면 라우터가 두 청크로 쪼개져 초기 로드가 오히려 늘어난다.
        advancedChunks: {
          groups: [
            {
              name: 'vendor-react',
              test: /node_modules[\\/](react|react-dom|react-router|react-router-dom)[\\/]/,
            },
            { name: 'vendor-axios', test: /node_modules[\\/]axios[\\/]/ },
          ],
        },
      },
    },
    chunkSizeWarningLimit: 800,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/__tests__/setup.ts'],
    exclude: ['node_modules/**', 'dist/**', 'e2e/**'],
    // 기본 5초는 이 스위트에는 짧다. 파일 하나만 돌리면 각 테스트가 1초 안에 끝나지만,
    // 124개 파일을 동시에 돌리면 워커 경합으로 jsdom 렌더·userEvent 가 5초를 넘겨 18건이
    // "Test timed out" 으로 죽었다(로직 실패가 아니라 굶주림이다). 실제 지연을 덮지 않도록
    // 넉넉하되 무한은 아닌 값으로 둔다 — 진짜 멈춘 테스트는 여전히 20초에 잡힌다.
    testTimeout: 20_000,
    hookTimeout: 20_000,
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
    coverage: {
      // ⚠ include 를 명시하지 않으면 vitest 는 **테스트가 import 한 파일만** 집계한다.
      // 그 상태의 "라인 91%"는 전체 src 153개 중 55개만 본 수치였고, 미테스트 화면은
      // 분모에서 통째로 빠져 있었다(전체 기준 실측 47.4%). 테스트가 없는 파일일수록
      // 커버리지가 좋아 보이는 역설이라, 게이트가 아니라 착시였다.
      //
      // 이제 src 전체를 분모로 고정한다 — 새 화면을 테스트 없이 추가하면 커버리지가
      // 떨어져 PR 에서 막힌다. 그게 이 게이트의 목적이다.
      include: ['src/**/*.ts', 'src/**/*.tsx'],
      exclude: ['src/__tests__/**', '**/*.test.ts', '**/*.test.tsx', '**/*.d.ts'],
      //
      // 라인 90% — 백엔드 JaCoCo 게이트(LINE 0.90)와 같은 기준선.
      // 실측 90.9%(2026-08-15, 전체 src 기준) — 임계치 대비 약 55줄 여유다. 임계치를 실측치까지
      // 바짝 올리지 않는 이유가 이 여유다: 기능 한 줄 추가에 게이트가 우는 상태면 다들 임계치를
      // 내리는 쪽으로 대응하게 되고, 그러면 게이트가 숫자만 남는다. 여유가 소진되면
      // 임계치를 내리지 말고 새 코드에 테스트를 붙일 것.
      //
      // 축을 하나씩 잠근다. 아직 90 에 못 미치는 축을 미리 걸면 곧바로 빨간 게이트가 되고,
      // 그러면 아무도 신뢰하지 않게 된다 — 올라간 축부터 잠근다.
      //
      // 2026-08-22 실측(전체 src 기준): lines 93.4% · statements 91.5% · branches 86.4% ·
      // functions 87.9%. statements 가 90 을 넘어 여유 약 135문(1.5%p)이 생겨 함께 잠근다.
      // branches·functions 는 아직 미달이라 그대로 둔다 — 올라오면 그때 추가할 것.
      thresholds: { lines: 90, statements: 90 },
    },
  },
  server: {
    port: 3000,
    proxy: {
      // 알림 푸시 SSE — operation-service(8092)의 notification 슬라이스가 서빙한다(ADR 0041).
      // 아래 '/api'(order 8088)보다 먼저 선언해야 우선 매칭된다. 이 항목이 없으면 dev 에서만
      // 알림 구독이 order-service 로 가서 조용히 실패한다.
      '/api/notifications': {
        target: 'http://localhost:8092',
        changeOrigin: true,
      },
      // 관제 콘솔·게시판 조회도 operation(8092)이 서빙한다 — 같은 이유로 '/api' 앞이다.
      '/api/ops': {
        target: 'http://localhost:8092',
        changeOrigin: true,
      },
      '/api/boards': {
        target: 'http://localhost:8092',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
      // 게시판·교육 관리 API 는 operation(8092)이 서빙한다 — 아래 '/admin'(order 8088)보다
      // 먼저 선언해야 우선 매칭된다. 이 두 항목이 없으면 dev 에서만 관리 화면이 order 로 가서
      // 조용히 404 가 된다(게이트웨이를 타는 preview·운영에서는 정상이라 더 늦게 발견된다).
      '/admin/boards': {
        target: 'http://localhost:8092',
        changeOrigin: true,
      },
      '/admin/education': {
        target: 'http://localhost:8092',
        changeOrigin: true,
      },
      '/admin': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
      '/auth': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
      '/games': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
    },
  },
})
