/**
 * ESLint 설정 — flat config(ESLint 10 + @typescript-eslint 8 기준).
 *
 * eslintrc(.eslintrc.cjs) 에서 이관했다. ESLint 9 부터 flat config 가 기본이고 `--ext` 플래그가
 * 사라졌다 — 대상 파일은 이제 각 config 블록의 `files` 가 소유한다.
 *
 * 타입 인지(type-aware) 규칙은 켜지 않는다: `parserOptions.project` 를 걸면 tsconfig 에서 제외된
 * 파일(e2e·테스트·설정 스크립트)이 전부 파싱 오류가 나고, 린트가 typecheck 를 중복 수행해 느려진다.
 * 타입 검증은 `npm run typecheck`(tsc) 가 정본이고, 여기서는 tsc 가 못 잡는 축(훅 규칙·미사용
 * 심볼·명백한 실수)만 본다.
 *
 * ★ 이관 원칙: 규칙 집합을 바꾸지 않는다. 도구 버전만 올리고 무엇을 잡는지는 그대로 둔다.
 *   특히 eslint-plugin-react-hooks 7 의 프리셋은 React Compiler 계열 규칙 15종(immutability,
 *   purity, set-state-in-effect …)을 새로 켜는데, 그걸 여기서 함께 켜면 도구 이관인지 리팩터링
 *   캠페인인지 구분이 안 된다. 그래서 프리셋을 쓰지 않고 기존 2종만 명시한다.
 *   새 규칙 도입은 별건으로 — 켤 때 실제 위반 건수를 보고 판단할 것.
 */
import js from '@eslint/js'
import globals from 'globals'
import tseslint from '@typescript-eslint/eslint-plugin'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'

export default [
  {
    ignores: [
      'dist',
      'coverage',
      'node_modules',
      'public',
      '.omc',
      'e2e/__screenshots__',
      'android',
      'ios',
      // eslintrc 시절 `--ext ts,tsx` 가 하던 범위 제한을 flat config 에서 재현한다. flat config 는
      // js/mjs/cjs 를 기본 대상으로 잡는데, 여기 걸리는 건 일회성 repro 스크립트와 PWA 에셋
      // 생성기뿐이고 둘 다 page.evaluate 안에서 브라우저 전역을 쓴다 — Node 파일로 린트하면
      // window·PopStateEvent 가 전부 no-undef 오탐이 된다. 린트 범위 확대는 도구 이관과 별개
      // 결정이므로 여기서 함께 하지 않는다.
      '**/*.{js,mjs,cjs}',
    ],
  },

  js.configs.recommended,

  // 파서·플러그인 등록 + `eslint-recommended` 상쇄(TS 가 이미 잡는 base 규칙을 끈다: no-undef 등).
  ...tseslint.configs['flat/recommended'],

  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser },
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      // eslintrc 시절 plugin:react-hooks/recommended 가 켜던 것과 동일한 2종(위 이관 원칙 참고).
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      // Vite HMR 경계 — 컴포넌트 파일에서 상수 외 값을 함께 export 하면 갱신이 깨진다.
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // `_` 접두 인자는 의도적 미사용(콜백 시그니처 맞추기)이므로 통과시킨다.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      // no-explicit-any 는 recommended 의 기본값(error)을 그대로 쓴다 — 도입 당시 83건이던 부채를
      // 전부 정리했다(catch 절은 @/lib/apiError, 토스 전역은 types/tosspayments.d.ts,
      // 정산 필터는 제네릭 키, 인터셉터 테스트는 최소 타입). 다시 warn 으로 낮추지 말 것.
    },
  },

  {
    // Node 컨텍스트에서 도는 것들 — 빌드·테스트 설정, 일회성 스크립트, Playwright e2e.
    files: [
      '**/*.cjs',
      '**/*.config.{js,ts}',
      'eslint.config.js',
      'e2e/**/*.ts',
      'playwright.config.ts',
      'repro-settlement.mjs',
      'scripts/**/*.{js,mjs}',
    ],
    languageOptions: {
      globals: { ...globals.node },
    },
  },

  {
    // 유닛 테스트 — jsdom + node 유틸을 함께 쓴다.
    files: ['src/__tests__/**/*.{ts,tsx}', '**/*.test.{ts,tsx}'],
    languageOptions: {
      globals: { ...globals.node },
    },
  },
]
