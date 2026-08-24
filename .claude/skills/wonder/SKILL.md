---
name: wonder
description: |
  사용자가 던진 단어/요청 안에 또 어떤 의미가 숨어 있는지 발산하듯 더 캐낸다. Socrates 루프에서는 현재 Cycle의 ### Wonder 섹션만 채운다.
  Triggers (KO): wonder, /wonder, 의미 더 캐줘, 숨어 있는 뜻 찾아줘
  Triggers (EN): wonder, expand meaning, explore hidden meanings
  Do NOT use when: 의미 차이를 비춰야 할 때 (-> reflect), shared meaning으로 합쳐야 할 때 (-> refine), goal 한 줄로 재진술해야 할 때 (-> restate), 전체 Seed 루프가 필요할 때 (-> socrates)
  vs reflect: wonder는 의미 후보를 발산하고, reflect는 후보와 사용자 의도 사이의 차이를 비춘다.
---

# Wonder

## 상세 설명

요청 안에 숨어 있는 의미 후보를 최소 3개까지 발산하는 단계다. 단독 호출 시에도 `.claude/scratch/socrates.md`에 현재 cycle을 만들고 `### Wonder`에 저장한다.

## 역할 — 소크라테스의 질문
> "방금 말씀하신 그건 또 어떤 의미를 가질 수 있을까요?"
> "그 안에 숨어 있는 다른 뜻은 없습니까?"
> "한 겹 더 들어가면 무엇이 보이나요?"

이 질문은 모두 AskUserQuestion 도구로 사용자에게 던진다. 모델이 본문에 의미를 자유 서술해 채우지 않는다. 의미 후보가 셋 이상 모일 때까지 같은 단계 안에서 한 겹씩 더 묻는다.

## 원칙
1. 한 번에 한 질문만 던진다.
2. 사용자가 꺼낸 의미를 평가하지 않는다 — 더 캐낸다.
3. 추측으로 의미를 채우지 않는다. 모르면 다시 묻는다.

## 출력 형식
의미 후보 3개 이상의 마크다운 리스트. 각 후보에 한 줄 설명.

- 의미 A — 한 줄 설명
- 의미 B — 한 줄 설명
- 의미 C — 한 줄 설명

## 저장 위치
`.claude/scratch/socrates.md`의 현재 `## Cycle N` 아래 `### Wonder` 섹션에 위 리스트를 기록한다. 활성 cycle이 없으면 `## 사용자 입력`과 `## Cycle 1`을 만든 뒤 `### Wonder`를 채운다. 의미가 셋 이상 모이면 저장하고 종료, 다음은 `/reflect`로 넘긴다. 사용자 원문은 `## 사용자 입력` 섹션에 그대로 보존한다.
