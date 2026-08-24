# 상품 카테고리·옵션 테이블 설계  

> 대상: `order-service` (opslab). 상태: **Phase 1–7 구현 완료 + 운영 표면까지 배선**
> (§4 이관 계획의 단계별 마이그레이션, §7 이후 붙은 것).
> 사라진 적이 있다 — 로컬 디스크에만 있는 정본은 협업·CI 에서 없는 것과 같으므로 원래 경로로 되살렸다.

---

## 1. 선행 사례 분석

### 1.1 드 트리 + 옵션 정의/판매옵션 2층

**카테고리**

| 테이블 | 역할 | 핵심 컬럼 |
| --- | --- | --- |
| `TBL_PRD_CTY_CODE` | 분류 트리(정본) | `cty_code`(코드 PK), `cty_pcode`, `cty_depth`, `code_name`, `code_name2`~`code_name5` |
| `TBL_PTN_CATE` | 파트너별 카테고리 노출 화이트리스트 | `ptncode`, `cty_code` |
| `TBL_PTN_CATE_POINT` | 카테고리별 적립 정책 | `cty_code`, `point_start`, `point_end`, `sortno` |
| `TBL_PROMOTION_CTY_CODE` | 기획전 전용 카테고리 | (진열/기획전 구분은 VO 의 `gubun`) |

조회는 자기조인 3단(`A LEFT JOIN A B ON A.CTY_PCODE=B.CTY_CODE LEFT JOIN A C ON B.CTY_PCODE=C.CTY_CODE`)으로
depth 3 경로를 한 방에 만든다. 네비게이션은 `WITH PRD_CODE_TABLE AS (...)` CTE.

가져올 것 / 버릴 것
- ✅ **파트너별 노출 분리**(`TBL_PTN_CATE`) — 하나의 카탈로그를 대상별로 다르게 진열. 개념이 옳다.
- ✅ **카테고리를 정책 부착점으로 사용**(`TBL_PTN_CATE_POINT`) — 분류가 곧 정책 키가 되는 구조.
- ❌ **다목적 슬롯 컬럼**(`code_name2`~`code_name5`). 실제로 `ORDER BY TO_NUMBER(A.CODE_NAME4)` 로 쓰인다.
  정렬키를 문자열 컬럼에 넣고 런타임 캐스팅 → 인덱스 불가 + 값 오염 시 조회 전체가 ORA-01722 로 죽는다.
- ❌ depth 를 자기조인 횟수로 고정 — 4단이 필요해지면 쿼리를 전부 고쳐야 한다.

**옵션** — 정의(축/값)와 판매옵션(SKU)을 분리한 2층 구조

```
TBL_PRODUCT_OPT_GROUP_DEFINE (opt_group_idx, prid, opt_group_name, opt_group_required, opt_group_ord)
        └─ TBL_PRODUCT_OPT_DEFINE (opt_idx, opt_group_idx, opt_name)          -- 축의 값

TBL_PRODUCTOPTION (poid, prid, proptionname, proptionkind, parent_poid,
                   proptionaddamt      -- 재고
                   proptionaddprice / proptionaddsellprice / proptionaddbuyprice,
                   proptionshowyn / proptiondispyn / proptiondelyn / proptionrequired,
                   proptionmarginrate / proptionenurirate, prst, ...)
```

가져올 것 / 버릴 것
- ✅ **옵션 축/값을 테이블로 정규화**하고 `required`·`ord` 를 축에 둔 것. 현재 우리 프로젝트에 없는 층이다.
- ❌ **`parent_poid` 자기참조 2단**. 2차까지만 조합 가능하고, 장바구니가 `OPTCODE`/`OPTCODE2` 두 컬럼으로
  고정되어 그 한계를 스키마에 굳혀버렸다(`TBL_CART_OPTLIST`, `TBL_DIRECT_ORDER_OPTLIST`).
- ❌ **재고를 부모 옵션에만** 둔다. 자식 조회 시
  `NVL((SELECT PROPTIONADDAMT FROM TBL_PRODUCTOPTION WHERE POID = CHILD.PARENT_POID), 0)` 로 부모 재고를 끌어온다
  → "빨강/L 만 품절"을 표현할 수 없다. 조합 단위 재고가 아니면 초과판매를 막지 못한다.
- ❌ 노출/삭제 플래그가 `showyn`·`dispyn`·`delyn` 3개로 흩어져 의미가 겹친다.
- ❌ `proptionmarginrate`·`proptionenurirate`(마진율·에누리율)가 옵션 행에 섞임 — 정산 관심사의 침투.

### 1.2 경로 비정규화 + 카운트 캐시, 그러나 SKU 부재

**카테고리**

| 테이블 | 역할 | 비고 |
| --- | --- | --- |
| `tb_category` | 분류 트리 | `parentId`, `depth(0/1/2)`, `displaySeq`, `isView` / **`isSortView`,`sort`**, `isDel` |
| `tb_category_special` | 기획전 카테고리 | `tb_category` 와 필드셋이 거의 동일한 **복제 엔티티** |
| `tb_market_categorylist` | **상품별 카테고리 경로 비정규화** | `category1_id`~`category5_id` + `category1_name`~`category5_name` + `productId` |
| `tb_category_cnt_sub` | **카테고리별 상품수 캐시** | `(parentId, product_id, category_id, count)` |
| `tb_market_promocategory` | 상품 ↔ 기획전 매핑 | `promocategoryName` 비정규화 포함 |

가져올 것 / 버릴 것
- ✅ **경로 비정규화 테이블** — 카테고리 리스팅에서 재귀 조인을 없앤다. 대형 카탈로그의 정답에 가깝다.
- ✅ **카운트 캐시 테이블** — 트리에 상품수 뱃지를 다는 순간 필요해진다.
- ⚠️ 경로에 **id 와 name 을 함께** 박아 카테고리명 변경 시 동기화 부채가 생긴다 → **ID만 비정규화**하고
  이름은 조인 또는 이벤트 갱신으로.
- ❌ `Category.product` 가 `@ManyToOne(Product)` — 카테고리가 상품을 참조한다. 트리 행이 상품 수만큼 복제된다.
  (실제로 `tb_market_categorylist` 가 따로 존재하는 걸 보면 이 매핑은 사고에 가깝다.)
- ❌ **분류 노출(`isView`/`displaySeq`)과 진열 노출(`isSortView`/`sort`)이 같은 테이블에 4컬럼으로 공존**.
  진열은 카테고리의 속성이 아니라 별도 축이다.
- ❌ 기획전을 별도 엔티티로 복제 → 트리 로직이 두 벌.

**옵션**

```
tb_market_opt      (seq, productCode, optionSerial, name)                  -- 옵션 그룹
tb_market_opt_item (seq, productCode, optionSerial, itemSerial, name,
                    price /* String! */, openYn, stockYn)                   -- 옵션 값
tb_cart_market_option (cartMarketSeq, memberNo, productId, productCode,
                    optionGroupNo, optionSerial, optionItemSerial,
                    optionItemName, optionItemPrice, optionItemCount, optionItemSubPrice)
```

가져올 것 / 버릴 것
- ✅ **장바구니/주문에 옵션 값 스냅샷**(이름·단가·수량·소계)을 남긴 것. 옵션이 바뀌거나 삭제돼도 문서가 읽힌다.
- ❌ **옵션 조합 SKU 개념이 없다.** 재고는 `Product.qty` 단일값이고 `openYn`/`stockYn` 은 수동 플래그다.
  옵션별 재고를 지탱할 수 없다.
- ❌ **`price` 가 `String`** — 우리 가드레일(금액 `BigDecimal` 강제) 정면 위반.
- ❌ `optionSerial`/`itemSerial` 이 "시분초 포함 문자열" 자연키. 충돌 가능·정렬 불가·조인 비용.
- ❌ `tb_market_opt` 에 엔티티가 **두 개**(`ProductOpt`, `ProductNewOption2`) 매핑돼 있다. 옵션 스키마 교체를
  하다 만 흔적 — 옵션 구조는 한 번 굳으면 바꾸기가 대단히 어렵다는 증거로 읽어야 한다.
- ❌ `Product.stockUseYn`("품절여부")·`soldoutYn`("옵션여부") — 주석과 이름이 서로 어긋나 있다.

---

## 2. 현재 프로젝트 진단

### 2.1 카테고리 — 두 벌이 공존한다

| 테이블 | 도입 | 상품 연결 | 특징 |
| --- | --- | --- | --- |
| `categories` | V12 | `products.category_id` (**1:N**) | `name` 글로벌 UNIQUE, `display_order`, 하드 삭제 |
| `ecommerce_categories` | V13 | `product_ecommerce_categories` (**M:N**) | `slug` UNIQUE, `depth<=2` CHECK, `sort_order`, soft delete |

- 상품이 카테고리에 붙는 경로가 **2개**이고, 도메인도 두 벌이다
  (`product.domain` 의 `Category*` / `category.domain` 의 `EcommerceCategory*`).
- 진열·기획전 개념 없음. 카테고리별 상품수 없음. 경로 조회는 매번 재귀.

### 2.2 옵션 — 표현은 JSON, 재고는 SKU, 축/값 테이블은 없음

```
opslab.products.options_json  JSONB   -- 임의 깊이 옵션 트리(표시용 원천)
opslab.product_variants               -- SKU: 재고·가산금·할인·상태
    option_name VARCHAR(200)          -- "색상:빨강/사이즈:L"
    UNIQUE (product_id, option_name)
```

`ResolveOptionSelectionService` 는 JSON 트리로 선택 경로를 검증한 뒤 `"축:값"` 을 `/` 로 이어붙여
문자열을 만들고, `loadByProductId(productId)` 전량을 가져와 **선형 스캔**으로 variant 를 찾는다.

문제 5가지
1. **옵션 축·값이 테이블에 없다.** "색상=빨강인 상품 전체" 같은 파셋 검색/필터가 원리적으로 불가능하다.
2. **문자열이 사실상 조인키다.** 순서·구분자·공백·이름 변경 어디가 틀려도 조용히 못 찾는다.
   `UNIQUE(product_id, option_name)` 이 이 규약을 스키마에 굳혔다 —  의 `OPTCODE/OPTCODE2` 와 같은 실수다.
3. **이름 변경 시 3곳이 어긋난다**: `options_json` / `product_variants.option_name` / 주문 스냅샷.
4. `resolve()` 가 O(변형 수) 스캔이고 인덱스를 못 탄다.
5. 옵션 축이 상품별로만 존재 → "사이즈" 가 판매자마다 제각각이라 표준화·통계가 안 된다.

---

## 3. 설계안

### 3.0 결정 4가지

1. **분류 트리는 `ecommerce_categories` 로 단일화**한다. `categories` / `products.category_id` 는 이관 후 제거.
2. **분류(카테고리)와 진열(전시·기획전)을 분리**한다. 분류는 상품의 성질, 진열은 기간·타깃이 있는 편성이다.
    가 `isView`/`isSortView` 를 한 테이블에 섞은 실수를 반복하지 않는다.
3. **옵션은 3층**으로 나눈다 — ① 표준 축/값 카탈로그(재사용) ② 상품이 채택한 축/값(차수·필수·정렬)
   ③ 조합 SKU(재고·가격). 그리고 ②↔③ 을 잇는 **매핑 테이블**을 조인키로 삼는다.
4. **`option_name` 문자열 규약을 조인키에서 표시용으로 강등**한다. 정확 조회는 `option_signature`(해시)로.

### 3.1 카테고리 (분류)

```sql
-- 기존 ecommerce_categories 확장. depth<=2 CHECK 는 유지.
ALTER TABLE opslab.ecommerce_categories
    ADD COLUMN IF NOT EXISTS path_ids       BIGINT[],          -- 루트→자기 [1,4,8]
    ADD COLUMN IF NOT EXISTS path_slug      VARCHAR(900),      -- 'electronics/computers/laptops'
    ADD COLUMN IF NOT EXISTS product_count  INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_ecommerce_categories_path_ids
    ON opslab.ecommerce_categories USING GIN (path_ids);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecommerce_categories_path_slug
    ON opslab.ecommerce_categories (path_slug) WHERE deleted_at IS NULL;
```

- `path_ids` 는 **ID만** 비정규화한다( 가 name 까지 박아 만든 부채를 피한다).
  하위 트리 전체 조회는 `WHERE path_ids @> ARRAY[:categoryId]` 한 줄 — 재귀 CTE가 사라진다.
- 이름은 조인으로 얻는다. 표시용 전체 경로명이 필요하면 읽기 모델에서 조립한다.
- `path_ids`/`path_slug` 는 **부모 변경(`changeParent`) 시 서브트리 일괄 갱신**이 필요하다.
  `EcommerceCategory.changeParent()` 가 이미 도메인 불변식(순환·깊이)을 강제하므로,
  경로 재계산은 persistence 어댑터가 아니라 애플리케이션 서비스에서 서브트리를 다시 쓰는 방식으로 붙인다.
- `product_count` 는 캐시다. 정본은 `product_ecommerce_categories` 의 실계수 — 배치/이벤트로 갱신하고
  불일치는 `/admin/integrity` 계열 점검에 항목 하나로 추가한다.

```sql
-- 대표 카테고리 1개 강제 (M:N 을 유지하면서 '주 분류'를 표현)
ALTER TABLE opslab.product_ecommerce_categories
    ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_product_primary_category
    ON opslab.product_ecommerce_categories (product_id) WHERE is_primary;
```

`is_primary` 가 폐기될 `products.category_id` 의 역할을 대체한다.

> **경계 주의 — 카테고리별 수수료율은 여기에 두지 않는다.**
>  의 `TBL_PTN_CATE_POINT` 처럼 카테고리를 정책 부착점으로 쓰고 싶어지지만, 정산 수수료 정책은
> `settlement-service` 소유다(등급별 3.5/2.5/2.0%, 정산시점 `commission_rate` 영구보존).
> `order-service` 는 상품 이벤트에 카테고리 식별자를 실어 보내고, 정책 판단은 settlement 가 자기
> 프로젝션에서 한다. 적립·쿠폰처럼 **커머스 소유** 정책만 카테고리에 붙일 수 있다.

### 3.2 진열 (전시·기획전)

```sql
CREATE TABLE opslab.display_sections (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(50)  NOT NULL UNIQUE,     -- 'MAIN_BEST', 'EXH_2026_SUMMER'
    name          VARCHAR(200) NOT NULL,
    kind          VARCHAR(30)  NOT NULL,            -- MAIN | EXHIBITION | CATEGORY_BEST
    category_id   BIGINT,                           -- CATEGORY_BEST 일 때만
    starts_at     TIMESTAMP,
    ends_at       TIMESTAMP,
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_display_sections_category
        FOREIGN KEY (category_id) REFERENCES opslab.ecommerce_categories (id) ON DELETE SET NULL,
    CONSTRAINT chk_display_sections_kind
        CHECK (kind IN ('MAIN', 'EXHIBITION', 'CATEGORY_BEST')),
    CONSTRAINT chk_display_sections_period
        CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE opslab.display_section_items (
    section_id  BIGINT  NOT NULL,
    product_id  BIGINT  NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    is_pinned   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (section_id, product_id),
    CONSTRAINT fk_display_items_section
        FOREIGN KEY (section_id) REFERENCES opslab.display_sections (id) ON DELETE CASCADE,
    CONSTRAINT fk_display_items_product
        FOREIGN KEY (product_id) REFERENCES opslab.products (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_display_items_section_sort
    ON opslab.display_section_items (section_id, is_pinned DESC, sort_order);
```

  기획전 카테고리(`TBL_PROMOTION_CTY_CODE`), 기획전 엔티티(`tb_category_special`)를
분류 트리와 **같은 모양으로 복제**했다. 여기서는 기획전을 트리가 아니라 **편성(section)** 으로 모델링해
트리 코드 중복을 없앤다. `kind` 로 메인 진열·기획전·카테고리 베스트를 한 테이블에서 다룬다.

### 3.3 옵션 (핵심)

```sql
-- ① 표준 옵션 축 카탈로그 (재사용)
CREATE TABLE opslab.option_axes (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,       -- 'COLOR', 'SIZE', 'CAPACITY'
    name        VARCHAR(100) NOT NULL,              -- '색상'
    input_type  VARCHAR(20)  NOT NULL DEFAULT 'SELECT',  -- SELECT | SWATCH | TEXT
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_option_axes_input_type
        CHECK (input_type IN ('SELECT', 'SWATCH', 'TEXT'))
);

-- ② 축의 표준 값
CREATE TABLE opslab.option_axis_values (
    id          BIGSERIAL PRIMARY KEY,
    axis_id     BIGINT       NOT NULL,
    code        VARCHAR(50)  NOT NULL,              -- 'RED'
    name        VARCHAR(100) NOT NULL,              -- '빨강'
    swatch_hex  VARCHAR(7),                         -- SWATCH 전용
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_option_axis_values_axis
        FOREIGN KEY (axis_id) REFERENCES opslab.option_axes (id) ON DELETE CASCADE,
    CONSTRAINT uq_option_axis_values_code UNIQUE (axis_id, code)
);

-- ③ 상품이 채택한 축 (차수 = sort_order)
CREATE TABLE opslab.product_option_axes (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT  NOT NULL,
    axis_id     BIGINT  NOT NULL,
    sort_order  INTEGER NOT NULL,                   -- 0=1차, 1=2차, ... (무제한)
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_product_option_axes_product
        FOREIGN KEY (product_id) REFERENCES opslab.products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_option_axes_axis
        FOREIGN KEY (axis_id) REFERENCES opslab.option_axes (id) ON DELETE RESTRICT,
    CONSTRAINT uq_product_option_axes UNIQUE (product_id, axis_id),
    CONSTRAINT uq_product_option_axes_order UNIQUE (product_id, sort_order)
);

-- ④ 상품이 노출하는 값 (전체 표준값 중 이 상품이 파는 것만)
CREATE TABLE opslab.product_option_values (
    id                     BIGSERIAL PRIMARY KEY,
    product_option_axis_id BIGINT  NOT NULL,
    axis_value_id          BIGINT  NOT NULL,
    sort_order             INTEGER NOT NULL DEFAULT 0,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_product_option_values_axis
        FOREIGN KEY (product_option_axis_id) REFERENCES opslab.product_option_axes (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_option_values_value
        FOREIGN KEY (axis_value_id) REFERENCES opslab.option_axis_values (id) ON DELETE RESTRICT,
    CONSTRAINT uq_product_option_values UNIQUE (product_option_axis_id, axis_value_id)
);

-- ⑤ 조합 SKU ↔ 선택값 매핑 (파셋 검색·정확 조회의 조인키)
CREATE TABLE opslab.product_variant_option_values (
    variant_id              BIGINT NOT NULL,
    product_option_axis_id  BIGINT NOT NULL,
    product_option_value_id BIGINT NOT NULL,
    PRIMARY KEY (variant_id, product_option_axis_id),   -- 축당 값 1개 강제
    CONSTRAINT fk_pvov_variant
        FOREIGN KEY (variant_id) REFERENCES opslab.product_variants (id) ON DELETE CASCADE,
    CONSTRAINT fk_pvov_axis
        FOREIGN KEY (product_option_axis_id) REFERENCES opslab.product_option_axes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pvov_value
        FOREIGN KEY (product_option_value_id) REFERENCES opslab.product_option_values (id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_pvov_value ON opslab.product_variant_option_values (product_option_value_id);

-- ⑥ 조합 서명: option_name 문자열을 조인키 자리에서 밀어낸다
ALTER TABLE opslab.product_variants
    ADD COLUMN IF NOT EXISTS option_signature VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_product_variants_signature
    ON opslab.product_variants (product_id, option_signature);
```

`option_signature` = 선택된 `(product_option_axis_id, product_option_value_id)` 쌍을 **축 id 오름차순으로
정렬해 이어붙인 문자열의 SHA-256 hex**. 도메인(`ProductVariant`)이 계산해 소유하고, 표시용 `option_name`
은 사람이 읽는 라벨로만 남긴다.

이 설계가 여는 것
- **정확 조회 O(1)**: 선택 → signature 계산 → `(product_id, option_signature)` 유니크 인덱스 단건 조회.
  현재의 전량 스캔 + 문자열 비교가 사라진다.
- **파셋 검색**: "색상=빨강인 판매중 SKU" 가 `product_variant_option_values` 조인 한 번.
- **차수 무제한**:  의 `parent_poid` 2단 한계, `OPTCODE/OPTCODE2` 고정을 구조적으로 회피.
- **조합 단위 재고**: 기존 `product_variants.stock_quantity` 를 그대로 쓴다.
   가 부모 옵션 재고를 자식이 상속하던 초과판매 위험이 없다.
- **이름 변경 안전**: 값 이름은 `option_axis_values.name` 한 곳. SKU·매핑은 ID로 묶여 있어 영향받지 않는다.

`products.options_json` 의 처지
- 위 테이블이 정본이 되면 `options_json` 은 **중복 진실원**이 된다. 등록 시점 원본 보존이 목적이라면
  읽기 전용 **감사 사본**으로 격하하고(컬럼 주석·용도 명시), 진열·선택 검증은 전부 테이블에서 읽는다.
  두 개를 모두 살아 있는 정본으로 두면  가 한 테이블에 엔티티 두 개를 매핑한 것과 같은 상태가 된다.

### 3.4 주문 옵션 스냅샷

`order_items` 는 현재 `variant_id`(nullable, `ON DELETE RESTRICT`)만 갖는다. 옵션 축/값이 테이블로 쪼개지면
"주문 당시 어떤 옵션을 골랐는지"가 조인 4개를 타야 복원되고, 값이 비활성화되면 복원이 흐려진다.

```sql
CREATE TABLE opslab.order_item_options (
    id               BIGSERIAL PRIMARY KEY,
    order_item_id    BIGINT       NOT NULL,
    axis_sort_order  INTEGER      NOT NULL,          -- 표시 순서(차수)
    axis_code        VARCHAR(50)  NOT NULL,          -- 스냅샷
    axis_name        VARCHAR(100) NOT NULL,
    value_code       VARCHAR(50)  NOT NULL,
    value_name       VARCHAR(100) NOT NULL,
    CONSTRAINT fk_order_item_options_item
        FOREIGN KEY (order_item_id) REFERENCES opslab.order_items (id) ON DELETE CASCADE,
    CONSTRAINT uq_order_item_options UNIQUE (order_item_id, axis_sort_order)
);
```

- 장바구니 스냅샷(`tb_cart_market_option`)에서 가져온 아이디어를 **주문에만** 적용한다.
- **금액은 스냅샷하지 않는다.** 라인 단가는 이미 `order_items` 가 보존하고,
  `ProductVariant.effectiveUnitPrice()` 의 우선순위(기준가 → 추가금 → 정액할인 → 정률할인 FLOOR)가
  환불 역산의 정본이다. 축별 가산금을 여기 또 적으면 합계가 두 곳에서 갈린다.
- **장바구니에는 스냅샷을 두지 않는다.** `cart_items` 는 `variant_id` 참조로 충분하다 — 장바구니 가격은
  조회 시점 재계산이 맞고, 스냅샷을 두면 가격이 낡는다.

---

## 4. 이관 계획 (각 단계가 독립 배포 가능)

| Phase | 내용 | 되돌릴 수 있는가 | 상태 |
| --- | --- | --- | --- |
| 1 | 옵션 축/값 카탈로그(①~④) 생성 + 시드. 기존 `option_name` 을 파싱해 축·값 역생성 | 예 (읽는 코드 없음) | ✅ `V20260813140000` |
| 2 | `product_variant_option_values`(⑤) 백필 + `option_signature`(⑥) 채움. **검증**: 모든 variant 가 signature 를 갖고 `(product_id, option_signature)` 충돌 0 | 예 | ✅ `V20260813150000` |
| 3 | `ResolveOptionSelectionService` 를 signature 단건 조회로 교체. `option_name` 은 표시 전용 | 예 | ✅ 폴백 제거 완료 |
| 4 | `UNIQUE(product_id, option_name)` 제거. `order_item_options` 추가 + 주문 생성 경로에 기록 | 조건부 | ✅ `V20260813160000` |
| 5 | 카테고리 단일화: `categories` → `ecommerce_categories` 이관, `is_primary` 설정, `products.category_id` 드랍 | 조건부 | ✅ `V20260813170000` |
| 6 | `path_ids`/`path_slug`/`product_count` 추가 + 갱신 경로(경로 전량 재계산, 카운트 갱신) | 예 | ✅ `V20260813180000` |
| 7 | `display_sections` / `display_section_items` 추가 | 예 | ✅ `V20260813190000` |

구현하며 설계에서 달라진 점 두 가지:

- **Phase 3 의 레거시 경로는 걷어냈다.** 이관 중에는 플래그가 아니라 상태(미백필 SKU)로 갈리게 두었고,
  백필 완료 후 제거했다. 다만 <b>백필만으로는 부족했다</b> — 생성 경로가 카탈로그를 채우지 않아
  새 SKU 는 계속 서명 없이 만들어졌기 때문이다. 그래서 `ProductVariantService` 가 생성 시점에
  백필과 <b>같은 규칙</b>으로 카탈로그를 등록하고, 등록 후에도 서명이 없으면 생성을 실패시킨다.
  "서명 없는 SKU 가 존재할 수 없다" 가 성립한 뒤에야 폴백을 지울 수 있다.
- **Phase 6 의 경로 재계산은 서브트리가 아니라 전량이다.** 부모 변경의 영향 범위를 좁게 잡으면
  경로가 조용히 어긋나는데, 카테고리 트리는 수백 행 규모라 전량 재계산이 더 싸고 확실했다.
- **Phase 7 의 공개 조회에 노출 판정이 빠져 있었다.** 편성 <b>목록</b>은 기간·활성으로 걸렀는데
  <b>항목</b>(`/display-sections/{code}/items`)은 걸르지 않아, 코드만 알면 시작 전 기획전의 라인업을
  미리 읽을 수 있었다(코드가 `EXH_2026_FALL` 처럼 규칙적이라 추측도 쉽다). 설계서가 "노출 판정은
  서버가 한다"고만 적고 <b>어느 표면까지</b>인지 적지 않은 탓이다 — 공개/운영 두 표면을 나눠
  `getVisibleItems`/`getItems` 로 갈랐다.

Phase 1–2 는 순수 추가라 무중단이다. Phase 4·5 가 되돌리기 어려운 지점이므로 **Phase 3 이 프로덕션에서
한 사이클 돈 뒤에** 착수한다. `tb_market_opt` 에 엔티티 두 개를 남긴 채 멈춘 것이
"옵션 구조를 절반만 바꾸면 영원히 절반인 채로 남는다"는 사례다.

## 5. 헥사고날 배치

```
order-service/src/main/java/github/lms/lemuel/
├── product/
│   ├── domain/
│   │   ├── OptionAxis.java              # code·name·inputType, 값 추가 규칙
│   │   ├── OptionAxisValue.java
│   │   ├── ProductOptionMatrix.java     # ★ 애그리거트: 상품의 축·값·조합 집합.
│   │   │                                #   - 선택 검증(차수 누락/과다/미존재 값)
│   │   │                                #   - option_signature 계산(정렬 규칙 소유)
│   │   │                                #   - 조합 폭발 상한(예: 축 5, 조합 500) 강제
│   │   └── ProductVariant.java          # (기존) optionSignature 필드 추가
│   ├── application/port/out/            # LoadProductOptionMatrixPort, SaveProductOptionMatrixPort
│   └── adapter/out/persistence/         # 5테이블 JPA 매핑 + 백필용 리포지토리
└── category/
    ├── domain/EcommerceCategory.java    # (기존) + path 재계산 규칙
    └── domain/DisplaySection.java       # 노출 기간·활성 판정
```

- `option_signature` 계산은 **도메인이 소유**한다. 어댑터나 SQL 로 새면 정렬 규칙이 두 벌이 되어
   의 `option_name` 문자열 규약과 똑같은 함정이 재현된다.
- 옵션 가산금은 `BigDecimal`/`NUMERIC` —  `price String` 을 그대로 반복하지 않는다.
- 도메인 public setter 금지, 팩토리/rehydrate 전용 — `guard.mjs` OO-* 규칙이 실시간으로 강제한다.
- 새 도메인 패키지가 붙으므로 **스캔·JPA·gateway·nginx·Dockerfile 5곳 배선**이 필요하다
  (`msa-service-wiring` 스킬 참조). 컴파일이 잡아주지 않고 런타임 404 로 조용히 실패한다.

## 6. 검증 항목

무엇으로 검증하는지까지 적는다 — 항목만 적힌 체크리스트는 "돌렸다고 믿는" 상태를 만든다.

| 항목 | 검증 수단 | 상태 |
| --- | --- | --- |
| 조합 단위 재고 차감(초과판매 0) | `VariantStockConcurrencyIT` | ✅ |
| signature 유일성·계산 규칙 | `OptionSignatureTest` · `ProductVariantSignatureTest` + `uq_product_variants_signature` | ✅ |
| 선택 → SKU 해석(집합 검증·순서 무관) | `ResolveOptionSelectionCatalogPathTest` | ✅ |
| 매핑 백필 멱등 | `BackfillVariantSignatureServiceTest` | ✅ |
| 파셋 조회(축·값 조인) | `ProductFacetJdbcAdapterIT` · `OptionFacetQueryTest` | ✅ |
| 카탈로그 쓰기 불변식(축 중복·SWATCH 표시색·TEXT 축) | `OptionCatalogAdminServiceTest` | ✅ |
| 카테고리 경로(`path_ids` 끝 = 자기 id, 길이 = depth+1) | `EcommerceCategoryPathTest` | ✅ |
| `product_count` 대사(캐시 vs 실계수) | `CategoryProductCountDriftTest` · `CheckCategoryCountIntegrityServiceTest` + `GET /admin/categories/count-integrity` | ✅ |
| 편성 노출 판정(기간·활성, 공개 표면) | `DisplaySectionTest` · `DisplaySectionServiceTest` · `PublicDisplaySectionControllerTest` | ✅ |
| 화면 배선(라우트 ↔ 메뉴) | `../../scripts/harness/test/menu-route-gate.test.mjs` | ✅ |
| 커버리지 게이트 | `./gradlew :order-service:test :order-service:jacocoTestCoverageVerification` (LINE 90%) | ✅ |

> 건수·수치는 여기 적지 않는다(적는 순간 낡는다). 규모가 궁금하면 위 명령을 돌린다.
>
> 남은 구멍: `product_count` 점검 쿼리는 단위 테스트로만 덮여 있다. `LIMIT :limit` 네이티브 쿼리와
> `Object[]` 매핑은 실제 PG 에서 돌아야 확실하므로 Testcontainers IT 가 하나 필요하다.

---

## 7. 이후 — Phase 7 뒤에 붙은 것

이관이 끝난 뒤 이 설계가 "열어 둔 것"들을 실제 표면으로 뽑아낸 기록이다. 테이블만 서 있고 아무도
읽지 않는 상태가 가장 위험하다 — 코드가 존재한다는 사실은 동작한다는 뜻이 아니다.

| 무엇 | 표면 | 근거 |
| --- | --- | --- |
| 옵션 파셋 검색 | `GET /api/products/facets` (`ProductFacetJdbcAdapter`) | §3.3 "파셋 검색" 이 처음 값을 냄 |
| 구매 화면 파셋 필터 | `components/product/OptionFacetPanel.tsx` | 소비 측 배선 |
| 표준 축·값 시드 | `V20260813220000__option_axis_seed.sql` | 축 갈라짐 축소 |
| 진열 편성 운영 콘솔 | `/admin/system/display-sections` + `GET /admin/display-sections/{code}/items` | Phase 7 진입점 |
| 공개 편성 항목 노출 판정 | `DisplaySectionService.getVisibleItems` | §4 셋째 "달라진 점" |
| 옵션 카탈로그 쓰기 API·화면 | `/admin/option-catalog/**` · `/admin/system/option-catalog` | 축 추가에 배포가 필요하던 것을 해소 |
| 상품수 캐시 정합 점검 | `GET /admin/categories/count-integrity` + 카테고리 화면 패널 | §3.1 이 요구한 "점검 항목" |

배선 규칙 두 가지가 여기서 반복해 걸렸다:

- **새 화면 = 라우트 + 메뉴 2스텝.** 메뉴 정본은 `menus` 테이블이라 시드 마이그레이션과
  `menuFallback.ts` 를 함께 고쳐야 하고, 어긋나면 `menu-route-gate` 가 CI 를 깬다.
- **공개 경로는 nginx 에도 있어야 한다.** `/display-sections/**` 는 게이트웨이에는 등록돼 있었지만
  `../../frontend/nginx.conf` 의 location 정규식에 없어 SPA 폴백(index.html)으로 새고 있었다. 200 HTML 이라
  조용히 실패한다 — `/admin` 접두 경로는 기존 세그먼트에 걸려 이 함정을 비껴간다.
