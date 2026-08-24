/**
 * 브랜드 지식 베이스 — 추천 상품의 브랜드가 파는 '의미'(히스토리 + 영적 마케팅).
 *
 * 영적 마케팅(spiritual marketing) 관점: 소비자가 사는 것은 물건이 아니라 그 브랜드의
 * 정신·세계관이라는 서사. 단, 포트폴리오 원칙상 창립연도·발상지·마일스톤은 실제 사실만
 * 담는다(과장·허위 금지). 라이선스 브랜드(코닥 어패럴·MLB)는 그 사실을 서사에 명시한다.
 */

export interface BrandHeritage {
  founded: number; // 창립/브랜드 성립 연도
  origin: string; // 발상지
  milestones: string[]; // 결정적 순간 (사실 기반, 2개)
}

export interface BrandSpirit {
  essence: string; // 브랜드가 파는 '의미' 한 줄
  narrative: string; // 영적 마케팅 서사 (세계관 ↔ 소비자 정체성, 2문장)
  tags: string[]; // 가치 태그 3개
}

export interface BrandStory {
  key: string;
  name: string;
  heritage: BrandHeritage;
  spirit: BrandSpirit;
}

/** 상품명 키워드 → 브랜드 key (없으면 null) */
const BRAND_KEYWORDS: { key: string; keywords: string[] }[] = [
  { key: 'northface', keywords: ['노스페이스'] },
  { key: 'uniqlo', keywords: ['유니클로', '히트텍'] },
  { key: 'mustandard', keywords: ['무탠다드'] },
  { key: 'kodak', keywords: ['코닥'] },
  { key: 'covernat', keywords: ['커버낫'] },
  { key: 'polo', keywords: ['폴로', '랄프로렌'] },
  { key: 'levis', keywords: ['리바이스'] },
  { key: 'nike', keywords: ['나이키'] },
  { key: 'newbalance', keywords: ['뉴발란스'] },
  { key: 'converse', keywords: ['컨버스', '척테일러'] },
  { key: 'drmartens', keywords: ['닥터마틴'] },
  { key: 'mlb', keywords: ['MLB'] },
  { key: 'acne', keywords: ['아크네'] },
];

/** 상품명에서 브랜드 key 추출 (매칭 없으면 null — 예: 일반 상품 '쿨코튼 반팔티') */
export function extractBrand(productName: string): string | null {
  for (const rule of BRAND_KEYWORDS) {
    if (rule.keywords.some((k) => productName.includes(k))) return rule.key;
  }
  return null;
}

export const BRAND_STORIES: Record<string, BrandStory> = {
  northface: {
    key: 'northface',
    name: '노스페이스',
    heritage: {
      founded: 1966,
      origin: '미국 샌프란시스코',
      milestones: [
        '1966 샌프란시스코의 작은 등산장비점에서 출발 — 브랜드명은 산의 가장 험한 북벽(North Face)에서 땄다',
        '1992 눕시(Nuptse) 다운 재킷 출시 — 히말라야 봉우리의 이름을 단 겨울 아이콘',
      ],
    },
    spirit: {
      essence: "옷이 아니라 '멈추지 않는 탐험(Never Stop Exploring)'의 정신을 판다",
      narrative:
        '노스페이스는 추위를 막는 도구가 아니라 정상을 향하던 등반가의 태도를 판다. 도심에서 이 패딩을 걸치는 사람은 방한이 아니라, 한계를 넘어서려는 탐험의 정신을 함께 입는다.',
      tags: ['도전', '자연', '탐험'],
    },
  },
  uniqlo: {
    key: 'uniqlo',
    name: '유니클로',
    heritage: {
      founded: 1984,
      origin: '일본 히로시마',
      milestones: [
        "1984 히로시마 1호점 개점 (Unique Clothing Warehouse — '유니클로')",
        '2003 도레이와 공동 개발한 발열 소재 히트텍(HEATTECH) 출시',
      ],
    },
    spirit: {
      essence: "유행이 아니라 누구의 삶에나 맞물리는 '부품(LifeWear)'을 판다",
      narrative:
        '유니클로는 트렌드를 좇는 대신, 모든 사람의 일상에 부품처럼 들어맞는 라이프웨어를 지향한다. 히트텍 한 장을 입는 것은 과시가 아니라, 매일의 기본을 조용히 완성하려는 실용의 태도를 택하는 일이다.',
      tags: ['실용', '일상', '기본'],
    },
  },
  mustandard: {
    key: 'mustandard',
    name: '무탠다드',
    heritage: {
      founded: 2017,
      origin: '대한민국 (무신사 자체 브랜드)',
      milestones: [
        "2017 무신사가 '기본에 충실한 가성비'를 내세운 자체 브랜드(PB)로 론칭",
        '합리적 가격의 슬랙스·기본 아이템으로 국내 베이식 웨어를 대중화',
      ],
    },
    spirit: {
      essence: "브랜드 프리미엄이 아니라 '기본에 충실한 표준(Standard)'을 판다",
      narrative:
        '무탠다드는 로고값을 덜어내고 매일 입을 기본만 남긴 무신사의 자체 브랜드다. 이 슬랙스를 고르는 것은 브랜드 과시 대신, 합리적인 값으로 실용을 택하는 태도다.',
      tags: ['실용', '가성비', '기본'],
    },
  },
  kodak: {
    key: 'kodak',
    name: '코닥 어패럴',
    heritage: {
      founded: 1888,
      origin: '미국 로체스터',
      milestones: [
        "1888 조지 이스트먼이 '버튼만 누르세요, 나머지는 우리가 합니다'로 카메라를 대중화",
        '필름 브랜드의 유산을 라이선스 어패럴로 확장 — 로고 헤리티지 웨어',
      ],
    },
    spirit: {
      essence: "필름에 순간을 담던 브랜드가 이제 '기억의 미학'을 입는다",
      narrative:
        '코닥 어패럴은 필름 브랜드의 유산을 라이선스로 입은 패션 라인으로, 순간을 기억으로 남긴다는 아날로그 미학을 옷에 옮긴다. 이 로고를 걸치는 사람은 제품이 아니라, 기록의 정서와 아날로그의 온기를 함께 걸친다.',
      tags: ['헤리티지', '개성', '아날로그'],
    },
  },
  covernat: {
    key: 'covernat',
    name: '커버낫',
    heritage: {
      founded: 2008,
      origin: '대한민국 서울',
      milestones: [
        "2008 'Cover the basic and Natural(기본과 자연스러움을 덮다)'이라는 이름으로 창립",
        '어센틱 아메리칸 캐주얼·서브컬처 감성으로 국내 스트리트 신을 대표',
      ],
    },
    spirit: {
      essence: "유행보다 '어센틱한 서브컬처'의 태도를 판다",
      narrative:
        '커버낫은 이름 그대로 기본과 자연스러움을 지향하며, 유행보다 어센틱한 서브컬처의 결을 좇는다. 이 옷을 입는 사람은 남을 따라가는 대신, 자기 취향을 드러내는 개성을 택한다.',
      tags: ['개성', '정통', '스트리트'],
    },
  },
  polo: {
    key: 'polo',
    name: '폴로 랄프로렌',
    heritage: {
      founded: 1967,
      origin: '미국 뉴욕',
      milestones: [
        '1967 랄프 로렌이 넥타이 컬렉션으로 시작',
        '1972 폴로 셔츠 출시 — 아메리칸 프레피 룩의 상징이 됨',
      ],
    },
    spirit: {
      essence: "옷이 아니라 '아메리칸 드림의 라이프스타일'을 판다",
      narrative:
        '폴로 랄프로렌은 넥타이 한 줄에서 시작해 하나의 라이프스타일 전체를 파는 브랜드가 되었다. 이 셔츠를 입는 것은 옷이 아니라, 품격 있는 클래식이라는 세계관을 걸치는 일이다.',
      tags: ['정통', '클래식', '품격'],
    },
  },
  levis: {
    key: 'levis',
    name: '리바이스',
    heritage: {
      founded: 1853,
      origin: '미국 샌프란시스코',
      milestones: [
        '1873 리벳으로 보강한 작업용 데님 특허 취득 (재봉사 야콥 데이비스와 공동)',
        "1890 '501' 로트 넘버 탄생 — 청바지의 원형",
      ],
    },
    spirit: {
      essence: "광부의 작업복에서 시작된 '자유와 정통'을 판다",
      narrative:
        '리바이스는 노동자의 작업복에서 태어나 150년을 견딘 데님의 원형이다. 501을 입는 사람은 유행이 아니라, 정통과 어디에도 매이지 않는 자유의 태도를 함께 입는다.',
      tags: ['정통', '자유', '헤리티지'],
    },
  },
  nike: {
    key: 'nike',
    name: '나이키',
    heritage: {
      founded: 1964,
      origin: '미국 오리건',
      milestones: [
        "1964 필 나이트와 코치 빌 바우어만이 '블루 리본 스포츠'로 시작 (1971 나이키로 개명)",
        "1988 'Just Do It' 캠페인 — 스포츠를 넘어 도전의 정신으로",
      ],
    },
    spirit: {
      essence: "'몸만 있으면 누구나 선수다' — 승리와 도전의 정신을 판다",
      narrative:
        "공동 창업자 빌 바우어만은 '몸이 있다면 당신은 이미 선수다'라고 말했다. 이 옷을 입는 것은 기능이 아니라, 망설임을 넘어 도전하는 'Just Do It'의 정신을 택하는 일이다.",
      tags: ['도전', '퍼포먼스', '승리'],
    },
  },
  newbalance: {
    key: 'newbalance',
    name: '뉴발란스',
    heritage: {
      founded: 1906,
      origin: '미국 보스턴',
      milestones: [
        '1906 아치 서포트(교정용 깔창) 회사로 보스턴에서 출발',
        "1982 '990' 출시 — 기능성과 장인정신의 러닝화 계보 시작",
      ],
    },
    spirit: {
      essence: "스타 모델 대신 '기능과 균형(New Balance)'이라는 장인정신을 판다",
      narrative:
        '뉴발란스는 오랫동안 유명 모델 없이 기능과 균형이라는 장인정신만으로 신발을 만들어 왔다. 993을 신는 사람은 과시가 아니라, 묵묵한 완성도와 균형이라는 태도에 값을 치른다.',
      tags: ['장인정신', '균형', '실용'],
    },
  },
  converse: {
    key: 'converse',
    name: '컨버스',
    heritage: {
      founded: 1908,
      origin: '미국 매사추세츠',
      milestones: [
        '1908 마퀴스 밀스 컨버스가 고무신발 회사로 창립',
        "1923 농구선수 척 테일러의 이름을 단 '올스타' 탄생 — 코트에서 거리 문화의 아이콘으로",
      ],
    },
    spirit: {
      essence: "농구화에서 청춘과 반항의 아이콘이 된 '변치 않는 개성'을 판다",
      narrative:
        '컨버스 척테일러는 농구화로 태어나 청춘과 반항의 아이콘이 되었고, 100년이 지나도 형태를 거의 바꾸지 않았다. 이 스니커즈를 신는 것은 유행이 아니라, 변치 않는 개성과 청춘의 태도를 신는 일이다.',
      tags: ['반항', '청춘', '개성'],
    },
  },
  drmartens: {
    key: 'drmartens',
    name: '닥터마틴',
    heritage: {
      founded: 1960,
      origin: '영국 (독일 발상)',
      milestones: [
        '1945 독일 의사 클라우스 마르텐스가 에어쿠션 밑창을 발명',
        "1961 '1461' 3홀 더비 출시 — 노동자화에서 펑크·서브컬처의 저항 아이콘으로",
      ],
    },
    spirit: {
      essence: "노동자의 튼튼한 신발에서 '반항과 자기표현'의 상징을 판다",
      narrative:
        '닥터마틴은 노동자의 튼튼한 부츠에서 출발해 펑크와 서브컬처의 저항을 상징하는 신발이 되었다. 1461을 신는 사람은 편안함만이 아니라, 순응하지 않는 자기표현의 태도를 함께 신는다.',
      tags: ['반항', '개성', '정통'],
    },
  },
  mlb: {
    key: 'mlb',
    name: 'MLB',
    heritage: {
      founded: 1997,
      origin: '대한민국 (F&F 라이선스)',
      milestones: [
        '1997 F&F가 메이저리그(MLB) 라이선스로 국내 론칭',
        '볼캡·모노그램으로 아시아 스트리트 패션 대표 브랜드로 성장',
      ],
    },
    spirit: {
      essence: "야구팀 로고에 담긴 '스포티한 스트리트 무드'를 판다",
      narrative:
        'MLB는 F&F가 메이저리그 로고를 라이선스해 만든 국내 스트리트 브랜드로, 야구의 스포티한 무드를 일상복으로 옮긴다. 이 볼캡을 쓰는 것은 특정 팀이 아니라, 가볍게 힘을 뺀 스트리트의 태도를 걸치는 일이다.',
      tags: ['스트리트', '개성', '일상'],
    },
  },
  acne: {
    key: 'acne',
    name: '아크네 스튜디오',
    heritage: {
      founded: 1996,
      origin: '스웨덴 스톡홀름',
      milestones: [
        "1996 스톡홀름에서 창작 집단 'Ambition to Create Novel Expressions(ACNE)'로 출발",
        '1997 로우 데님 100벌을 지인에게 선물하며 패션 하우스로 전환',
      ],
    },
    spirit: {
      essence: "미니멀한 스칸디나비아 감성으로 '예술적 절제'를 판다",
      narrative:
        "아크네 스튜디오는 '새로운 표현을 향한 야망'이라는 창작 집단에서 출발한 스웨덴의 미니멀 하우스다. 이 머플러를 두르는 사람은 장식이 아니라, 절제된 스칸디나비아의 예술적 감각을 두른다.",
      tags: ['개성', '미니멀', '예술'],
    },
  },
};

/** 브랜드 key → 스토리 (없으면 undefined) */
export function getBrandStory(key: string): BrandStory | undefined {
  return BRAND_STORIES[key];
}

/** 상황별 선호 가치 태그 (spirit.tags 와 매칭해 브랜드 점수 가점) */
export const SITUATION_BRAND_TAGS: Record<'DAILY' | 'OFFICE' | 'DATE' | 'SPORTY', string[]> = {
  SPORTY: ['도전', '퍼포먼스', '승리', '장인정신', '균형', '탐험'],
  OFFICE: ['정통', '클래식', '품격', '실용', '기본'],
  DATE: ['개성', '미니멀', '예술', '클래식', '품격'],
  DAILY: ['실용', '일상', '기본', '가성비', '스트리트', '청춘'],
};
