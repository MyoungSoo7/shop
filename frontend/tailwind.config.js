/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      minHeight: {
        /**
         * `min-h-screen`(50곳 사용)을 100vh → 100dvh 로 바꾼다.
         *
         * iOS Safari 의 100vh 는 **주소창이 접힌 상태**의 높이라, 주소창이 펼쳐진
         * 첫 화면에서 페이지가 뷰포트보다 커진다 → 화면 하단이 잘리고 스크롤이 튄다.
         * dvh 는 툴바 개폐를 따라가는 동적 높이라 모바일에서 의도한 "화면 한 장"이 된다.
         * 50곳을 개별 수정하는 대신 스케일 값 하나만 바꿔 전역에 적용한다.
         * (dvh: Safari 15.4+ / Chrome 108+ / Firefox 101+ — 지원 범위 충족)
         */
        screen: '100dvh',
      },
    },
  },
  plugins: [],
}
