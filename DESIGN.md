---
version: 1
name: env-license-app 업무 화면
description: KRDS(범정부 UI/UX 디자인 시스템) 바탕, 데이터가 많은 화면은 IBM Carbon 규칙 차용
status: 초안 — 사용자 승인 전. 화면 코드는 아직 이 문서를 따르지 않는다.
units: px (KRDS 원본은 1rem = 10px 기준 rem이다. 이 앱은 html 16px이라 px로 옮겨 적었다.)

color:
  # 강조색은 파랑 하나. 출처: KRDS primary 팔레트
  primary: "#256ef4"          # KRDS primary-50. 주 버튼 바탕, 선택 밑줄, 포커스 테두리
  primary-hover: "#0b50d0"    # primary-60. 주 버튼 hover
  primary-pressed: "#083891"  # primary-70
  primary-text: "#0b50d0"     # primary-60. 링크·파란 글자 (흰 바탕 6.83:1)
  primary-subtle: "#ecf2fe"   # primary-5. 안내 상자 바탕, 선택된 행
  primary-border-light: "#d8e5fd"  # primary-10. 안내 상자 테두리
  secondary: "#063a74"        # KRDS secondary-70. 탭 선택선(KRDS 기본). 이 앱에서는 거의 쓰지 않는다
  # 회색 단계 (KRDS gray)
  gray-0: "#ffffff"
  gray-5: "#f4f5f6"
  gray-10: "#e6e8ea"
  gray-20: "#cdd1d5"
  gray-30: "#b1b8be"
  gray-40: "#8a949e"
  gray-50: "#6d7882"
  gray-60: "#58616a"
  gray-70: "#464c53"
  gray-80: "#33363d"
  gray-90: "#1e2124"
  gray-95: "#131416"
  # 역할 이름 (KRDS 시맨틱 토큰을 따른다)
  background: "#ffffff"       # background-white
  surface-subtle: "#f4f5f6"   # surface-gray-subtler. 표 머리, 요약 띠, 원문 인용 상자
  surface-inverse: "#1e2124"  # surface-inverse. 상단 유틸리티 띠
  text-bolder: "#131416"      # 제목
  text-basic: "#1e2124"       # 본문
  text-subtle: "#464c53"      # 보조 글
  text-muted: "#6d7882"       # 날짜·단위 같은 부가 정보. 흰 바탕 전용 (4.51:1)
  text-disabled: "#8a949e"
  text-inverse: "#ffffff"
  text-inverse-subtle: "#b1b8be"  # 어두운 띠 위 보조 글 (8.06:1)
  border: "#cdd1d5"           # border-gray-light. 기본 테두리·구분선
  border-strong: "#b1b8be"    # border-gray
  border-input: "#58616a"     # input-border. 입력 테두리 (KRDS는 입력 테두리에 4.5:1 이상을 요구)
  divider-light: "#e6e8ea"    # 표 행 구분선 (추정: KRDS 표는 gray-20을 쓰지만 행이 많은 화면에서 한 단계 연하게)
  # 상태색: 글자 60, 바탕 5, 테두리 10, 아이콘 50 (KRDS 규칙)
  danger-text: "#bd2c0f"
  danger-bg: "#fdefec"
  danger-border: "#fcdfd9"
  danger-icon: "#de3412"
  warning-text: "#8a5c00"
  warning-bg: "#fff3db"
  warning-border: "#ffe0a3"
  warning-icon: "#9e6a00"
  success-text: "#267337"
  success-bg: "#eaf6ec"
  success-border: "#d8eedd"
  success-icon: "#228738"
  information-text: "#096ab3"
  information-bg: "#e7f4fe"
  information-border: "#d3ebfd"
  information-icon: "#0b78cb"
  highlight: "#ffe0a3"        # 원문 인용 속 노란 하이라이트. KRDS warning-10 (추정: KRDS에 하이라이트 토큰은 없다)
  focus-ring: "#256ef4"

typography:
  font-family: '"Pretendard GOV Variable", "Pretendard GOV", -apple-system, BlinkMacSystemFont, system-ui, "Apple SD Gothic Neo", "Noto Sans KR", "Malgun Gothic", sans-serif'
  font-weight: { regular: 400, medium: 500, bold: 700 }
  line-height: { body: 1.5, heading: 1.4, none: 1 }   # heading 1.4는 추정. KRDS는 150% 이상을 권장
  letter-spacing: 0
  size:
    display: 36       # KRDS display-small. 이 앱에서는 쓰지 않는다
    heading-lg: 32    # 페이지 제목 (드묾)
    heading-md: 24    # 페이지 제목 기본
    heading-sm: 19    # 구역 제목
    heading-xs: 17    # 카드·패널 제목
    heading-xxs: 15   # 표 머리글
    body-lg: 19
    body-md: 17       # KRDS 기본 본문
    body: 16          # 이 앱 본문 (KRDS 본문 최소 16px)
    body-sm: 15       # 표 본문, 보조 글
    body-xs: 13       # 각주·단위. 정보 전달 글에는 쓰지 않는다
    label-md: 17
    label-sm: 15

spacing:
  # KRDS number 단계에서 4px 배수만 골랐다
  "1": 2
  "2": 4
  "3": 8
  "4": 12
  "5": 16
  "6": 20
  "7": 24
  "8": 32
  "9": 40
  "10": 48
  "11": 64
  page-gutter: 24      # KRDS contents-padding-x
  content-max: 1200    # KRDS contents-size
  section-gap: 40      # 구역 사이 (추정: KRDS h2-h3 간격 40px을 업무 화면 구역 간격으로 차용)

radius:
  none: 0
  xs: 2       # 배지·태그·체크박스
  sm: 4       # 버튼·입력·카드·안내 상자·모달
  full: 9999  # 숫자 배지 점 표시에만
  # KRDS 원본 버튼 모서리는 4~8px다. 시안 결정에 따라 4px로 각지게 줄였다.

border:
  width: { regular: 1, medium: 2 }   # KRDS border-width regular 0.1rem, medium 0.2rem
  default: "1px solid #cdd1d5"
  input: "1px solid #58616a"
  focus: "2px solid #256ef4"   # KRDS는 0.4rem(4px) 바깥 테두리. 업무 화면 밀도를 고려해 2px + offset 2px로 줄였다 (추정)
  tab-active: "3px solid #256ef4"  # 시안의 파란 밑줄 (두께는 추정)

elevation:
  # KRDS 그림자 색: shadow1 #0000000d, shadow2 #00000014, shadow3 #0000001f
  none: none
  overlay: "0 4px 16px #00000014"   # 드롭다운·팝오버 (오프셋·흐림 값은 추정)
  modal: "0 8px 24px #0000001f"     # 모달·드로어 (추정)
  # 카드·패널·표에는 그림자를 쓰지 않는다. 테두리로 나눈다.

component:
  utility-bar: { height: 40, background: surface-inverse, text: text-inverse-subtle, font-size: 15 }  # 높이 추정
  main-nav: { height: 56, item-font-size: 17, item-weight: 700, active-indicator: tab-active, background: background, border-bottom: default }  # 높이 추정
  button:
    sizes: { sm: 40, md: 48, lg: 56 }   # KRDS small/medium/large. 기본은 md
    default-size: md
    radius: sm
    padding-x: { sm: 12, md: 16, lg: 20 }
    font-size: { sm: 15, md: 17, lg: 19 }
    font-weight: 400   # KRDS 버튼 글자는 regular. 주 버튼만 700 허용
    primary: { background: primary, hover: primary-hover, pressed: primary-pressed, text: text-inverse }
    secondary: { background: primary-subtle, border: "1px solid #256ef4", text: primary-text }
    tertiary: { background: background, border: "1px solid #58616a", hover: gray-5, text: text-basic }
    danger: { background: background, border: "1px solid #bd2c0f", text: danger-text }   # KRDS에 위험 버튼 변형은 없다 (추정)
    disabled: { background: gray-20, text: text-disabled }
  input:
    height: { sm: 40, md: 48, lg: 56 }   # KRDS input small/medium/large
    default-height: md
    radius: sm
    padding-x: 16
    border: border-input
    border-focus: primary
    border-error: danger-icon
    disabled-background: gray-20
    placeholder: text-disabled
  badge:
    height: 24      # KRDS badge size-height-4
    height-lg: 32
    padding-x: 8
    radius: xs
    font-size: 15
    style: "light"  # 연한 바탕(상태색-bg) + 상태색 글자 + 상태색 테두리
  alert:
    radius: sm
    padding: 16
    info: { background: primary-subtle, border: primary-border-light, icon: primary }
    quote: { background: surface-subtle, border-left: "3px solid #b1b8be", highlight: highlight }
  table:
    row-height: { compact: 40, default: 48 }   # Carbon md/lg. 헤더 높이는 본문 행과 같다
    header-background: surface-subtle
    header-font: { size: 15, weight: 700, color: text-bolder }
    body-font: { size: 15, weight: 400, color: text-basic }
    cell-padding-x: 16
    row-divider: divider-light
    row-hover: gray-5
    row-selected: primary-subtle
    toolbar-height: 48
    pagination: bottom
  tab:
    height: 48       # KRDS tab button height
    font-size: 17
    active: { text: text-bolder, weight: 700, indicator: tab-active }
    inactive: { text: text-subtle, weight: 400 }
  summary-strip:
    border: default
    radius: sm
    cell-padding: "16 20"
    cell-divider: "1px solid #cdd1d5"
    value-size: 24
    value-weight: 700
    label-size: 15
  modal: { radius: sm, padding: 24, max-width: 640, elevation: modal }
  touch-target-min: 44   # 추정: KRDS 문서에서 명시값을 찾지 못했다. WCAG 2.5.5(44px)를 따르고, 버튼·입력은 48px로 둔다
---

# env-license-app 디자인 규칙

이 문서는 화면을 만들거나 고칠 때 따르는 규칙이다.
값은 위 front matter 토큰을 쓴다.
토큰에 없는 색·크기를 새로 만들지 않는다.
필요하면 이 문서에 먼저 토큰을 추가한다.

## 1. 방향

- 바탕은 KRDS(범정부 UI/UX 디자인 시스템)다.
- 행이 많은 표·필터·상태 표시는 IBM Carbon 규칙을 빌린다.
- 목표는 공공기관과 보수적인 기업 실무자가 처음 봐도 익숙하고 믿음직한 화면이다.
- 꾸밈보다 읽기 쉬움과 정보 밀도를 앞세운다.
- 이 앱은 정부 누리집이 아니다. KRDS의 공식 배너 문구("이 누리집은 대한민국 공식 전자정부 누리집입니다"), 태극 문양, 정부 상징은 쓰지 않는다.

## 2. 기본 규칙

### 색

- 강조색은 파랑(`primary`) 하나다.
- 주 버튼, 선택된 메뉴 밑줄, 포커스 테두리, 링크에만 쓴다.
- 파란 글자는 `primary-text`(#0b50d0)를 쓴다. `primary`(#256ef4)는 회색 바탕에서 4.5:1이 안 된다(4.17:1).
- 상태색은 의미가 있을 때만 쓴다. 장식용으로 빨강·초록을 쓰지 않는다.
- 상태색은 KRDS 짝을 지킨다: 글자 60, 바탕 5, 테두리 10, 아이콘 50.
- 회색 글자는 세 단계만 쓴다: `text-basic`, `text-subtle`, `text-muted`.
- `text-muted`는 흰 바탕에서만 쓴다. 회색 바탕 위에서는 `text-subtle`을 쓴다.
- 그라디언트, 반투명 유리 효과, 네온·보라 계열은 쓰지 않는다.

### 글자

- 글꼴은 Pretendard GOV다. 못 불러오면 시스템 한글 글꼴로 넘어간다.
- 본문은 16px, 표와 보조 글은 15px다.
- 13px은 각주·단위에만 쓴다. 판단에 필요한 정보는 15px 이상으로 쓴다.
- 굵기는 400(본문), 700(제목·강조) 두 가지를 기본으로 한다. 500은 표 머리글과 탭에 필요할 때만 쓴다.
- 줄 간격은 본문 1.5 이상이다.
- 자간은 0이다. 한글 제목에 음수 자간을 주지 않는다.
- 숫자 열은 `font-variant-numeric: tabular-nums`로 자릿수를 맞춘다.
- 한글 줄바꿈은 `word-break: keep-all`로 단어 중간에서 끊지 않는다.

### 모양

- 모서리는 2px(배지)과 4px(그 밖 모두)만 쓴다.
- 알약 모양 버튼, 8px 넘는 둥근 카드는 쓰지 않는다.
- 면은 얇은 회색 테두리(1px `border`)로 나눈다.
- 그림자는 드롭다운·모달처럼 화면 위에 뜨는 것에만 쓴다.

### 크기

- 버튼과 입력은 48px이 기본이다.
- 표 안의 작은 버튼, 도구 막대 버튼만 40px을 허용한다.
- 누를 수 있는 것은 최소 44×44px 영역을 확보한다. 아이콘이 작으면 여백으로 채운다.

### 접근성

- 글자 대비는 4.5:1 이상, 24px 이상 큰 글자는 3:1 이상이다(KRDS·WCAG AA).
- 입력 테두리는 4.5:1 이상이 되도록 `border-input`을 쓴다.
- 상태는 색만으로 알리지 않는다. 반드시 글자(또는 아이콘 + 글자)를 함께 쓴다.
- 포커스는 2px 파란 테두리로 항상 보이게 한다. `outline: none`만 쓰고 대체 표시를 빼지 않는다.
- 아이콘만 있는 버튼에는 `aria-label`을 단다.

## 3. 공통 틀 (시안 기준)

위에서 아래 순서다.

1. **유틸리티 띠** — 어두운 회색(`surface-inverse`) 40px 띠. 회사 이름, 사용자 이름, 알림, 로그아웃을 오른쪽에 둔다. 글자는 15px `text-inverse-subtle`.
2. **주 메뉴** — 흰 바탕 가로 메뉴. 왼쪽에 앱 이름, 그 옆에 메뉴 항목(17px, 700). 선택된 항목은 3px 파란 밑줄과 `text-bolder`로 표시한다. 아래에 1px 회색 선.
3. **페이지 머리** — 위치 표시(필요할 때) → 페이지 제목(24px, 700) → 한 줄 설명(16px `text-subtle`). 주요 작업 버튼은 제목 오른쪽 끝에 둔다.
4. **본문** — 최대 폭 1200px, 좌우 여백 24px. 구역 사이 40px.

사이드바 메뉴는 프로젝트 상세처럼 하위 화면이 많은 곳에서만 쓴다.

## 4. 컴포넌트 규칙

### 버튼

- 한 화면 영역에 주 버튼(파란 바탕)은 하나만 둔다.
- 순서는 왼쪽부터 보조 → 주 버튼이다. 주 버튼은 오른쪽 끝에 둔다.
- 되돌릴 수 없는 작업(삭제·반려)은 `danger` 버튼(흰 바탕 + 빨간 테두리·글자)을 쓰고 확인 대화상자를 띄운다.
- 버튼 글자는 동사로 끝낸다: "저장", "승인", "반려". "확인"만 쓰지 않고 무엇을 하는지 적는다.

### 입력

- 라벨은 입력 위에 둔다(15px, 700). 자리표시자를 라벨 대신 쓰지 않는다.
- 필수 항목은 라벨 옆에 "(필수)"를 글자로 적는다. 빨간 별표만 쓰지 않는다.
- 도움말은 입력 아래 15px `text-subtle`.
- 오류는 입력 테두리를 `danger-icon`으로 바꾸고, 아래에 오류 아이콘 + 빨간 글자로 이유와 고치는 방법을 적는다.

### 상태 배지

- 연한 바탕 + 같은 계열 테두리 + 진한 글자다. 높이 24px, 모서리 2px.
- 글자에 상태와 수치를 함께 쓴다: "경과 3일", "D-4", "완료", "검토 중".
- 상태와 색의 짝은 아래로 고정한다.

| 상태 | 색 | 예 |
|---|---|---|
| 기한 지남·만료·반려 | danger | 경과 3일 |
| 기한 임박·조치 필요 | warning | D-4 |
| 완료·취득·승인 | success | 완료 |
| 진행 중·검토 중·안내 | information | 검토 중 |
| 시작 전·초안·해당 없음 | gray (`gray-5` 바탕, `gray-20` 테두리, `text-subtle` 글자) | 시작 전 |

- 색 이름을 화면 문구로 쓰지 않는다("빨간 항목" 금지).

### 안내 상자

- 일반 안내: 연한 파랑 바탕(`primary-subtle`) + 1px `primary-border-light` 테두리 + 왼쪽 정보 아이콘.
- 경고·오류 안내: 같은 모양에 warning·danger 짝을 쓴다.
- 원문 인용(법령·허가 문서 발췌): 회색 상자(`surface-subtle`) + 왼쪽 3px `border-strong` 선. 근거가 되는 구절만 노란 하이라이트(`highlight`)로 칠한다. 인용 아래에 출처(문서명·쪽·조항)를 13~15px로 적는다.

### 요약 숫자

- 카드 여러 장으로 흩지 않는다.
- 테두리 띠 하나 안에 칸을 나누어 넣는다. 칸 사이는 1px 세로선이다.
- 각 칸: 라벨(15px `text-subtle`) 위, 숫자(24px 700) 아래. 단위는 숫자 옆 15px.
- 숫자가 상태를 뜻하면(예: 기한 지남 3건) 숫자 색만 상태색 글자색으로 바꾼다.
- 칸을 누르면 해당 목록으로 필터를 걸어 이동한다.

### 탭

- 높이 48px, 17px 글자.
- 선택된 탭은 3px 파란 밑줄 + 700 굵기. 나머지는 `text-subtle` 400.
- 탭 줄 아래에 1px 회색 선을 깐다.
- 개수는 탭 이름 옆에 괄호로 적는다: "허가 (12)".

### 표 (Carbon 규칙 차용)

- 행 높이는 48px이 기본, 행이 많은 목록은 40px(compact)이다. 머리글 행 높이도 본문 행과 같게 한다. 한 표 안에서 섞지 않는다.
- 머리글: `surface-subtle` 바탕, 15px 700, 왼쪽 정렬.
- 본문: 15px, 숫자·날짜 열은 오른쪽 정렬 + 고정폭 숫자.
- 행 구분은 1px `divider-light` 가로선만 쓴다. 세로선과 줄무늬는 쓰지 않는다.
- 행에 마우스를 올리면 `gray-5` 바탕. 선택된 행은 `primary-subtle`.
- 정렬 가능한 열은 머리글에 화살표를 둔다(정렬 중이거나 hover일 때 표시).
- 표 위 도구 막대(48px): 왼쪽 검색, 오른쪽 필터·내보내기·설정. 도구 막대 작업은 다섯 개까지, 나머지는 더보기 메뉴로.
- 행을 고르면 도구 막대 자리에 일괄 작업 막대가 뜬다. "N건 선택됨"과 작업 버튼, 오른쪽 끝에 취소.
- 행 작업이 세 개 미만이면 행 끝에 아이콘 버튼, 세 개 이상이면 더보기 메뉴.
- 쪽 넘김은 항상 표 아래에 둔다.
- 불러오는 중에는 스피너 대신 행 모양 뼈대(skeleton)를 보여 준다.

### 필터

- 자주 쓰는 필터(상태·분류·담당)는 표 위에 가로로 늘어놓는다.
- 분류가 많으면 왼쪽 세로 패널로 옮긴다. 여러 분류를 드롭다운 하나에 몰아넣지 않는다.
- 하나만 고르는 필터는 드롭다운이나 라디오, 여러 개는 체크박스.
- 걸린 필터는 표 위에 칩으로 보여 주고, 칩마다 지우기와 "필터 모두 지우기"를 둔다.
- 필터를 패널·메뉴에 숨겼으면 닫힌 상태에서도 걸린 개수를 보여 준다: "필터 (2)".

### 모달·드로어

- 모서리 4px, 안쪽 여백 24px, 최대 폭 640px.
- 제목(19px 700) → 본문 → 아래 오른쪽 버튼 줄.
- Esc와 닫기 버튼으로 닫힌다. 열리면 첫 입력에 포커스를 둔다.

## 5. 화면 유형별 지침

### 대시보드

- 맨 위: 요약 숫자 띠 하나(기한 지남, 7일 안 마감, 검토 대기, 진행 중 프로젝트).
- 그 아래: "오늘 할 일" 표 — 기한 순 정렬, 상태 배지 열 포함.
- 그 아래: 프로젝트별 진행 표 또는 일정 요약.
- 차트는 판단에 꼭 필요할 때만 넣는다. 장식용 도넛 차트는 넣지 않는다.
- 카드 격자로 화면을 채우지 않는다.

### 목록·표 화면 (허가 목록, 프로젝트 목록, 받은 일)

- 페이지 머리 → 필터 줄 → 도구 막대 → 표 → 쪽 넘김.
- 첫 열은 대상 이름(링크, `primary-text`), 둘째 열은 상태 배지다.
- 기한 열은 날짜와 남은 날(D-4) 배지를 함께 둔다.
- 행을 누르면 오른쪽 드로어로 상세를 연다. 목록 위치는 그대로 둔다.

### 검토·비교 화면 (AI 검토, 개정 비교)

- 왼쪽: 원문(회색 인용 상자 + 노란 하이라이트). 오른쪽: AI가 뽑은 값 또는 바뀐 내용.
- 각 항목에 근거 위치(문서·쪽·조항)를 적는다.
- AI가 확신하지 못한 항목은 warning 배지 "확인 필요"를 단다.
- 하단 고정 버튼 줄, 오른쪽 정렬, 왼쪽부터:
  1. **반려** — `danger` 버튼
  2. **고쳐서 승인** — `tertiary` 버튼. 누르면 값을 고칠 수 있는 상태로 바뀐다
  3. **승인하고 다음** — `primary` 버튼. 승인 후 다음 검토 항목으로 넘어간다
- 남은 검토 건수를 버튼 줄 왼쪽에 적는다: "남은 검토 5건".

### 폼 (새 프로젝트, 허가 등록)

- 한 열로 쌓는다. 짧은 항목(시작일·종료일)만 한 줄에 둘.
- 관련 항목은 구역 제목(19px 700)으로 묶는다.
- 입력 폭은 내용 길이에 맞춘다. 날짜 입력을 화면 끝까지 늘이지 않는다.
- 저장 버튼은 폼 맨 아래 오른쪽. 긴 폼은 하단 버튼 줄을 고정한다.
- 저장 실패 시 폼 맨 위에 danger 안내 상자로 오류 개수를 알리고, 각 항목에 오류를 표시한다.

### 빈 상태

- 표·목록 자리에 가운데 정렬로 둔다.
- 한 줄 제목(무엇이 없는지) + 한 줄 설명(왜 없는지·다음에 할 일) + 버튼 하나.
- 예: "등록된 허가가 없습니다." / "프로젝트를 만들면 필요한 허가가 자동으로 채워집니다." / [허가 추가]
- 일러스트·이모지는 쓰지 않는다. 회색 선 아이콘 하나까지만 허용한다.
- 필터 결과가 없을 때는 "조건에 맞는 항목이 없습니다." + [필터 모두 지우기].

### 오류

- 항목 오류: 입력 바로 아래(위 입력 규칙).
- 화면 일부 오류(목록 불러오기 실패): 그 자리에 danger 안내 상자 + [다시 시도].
- 화면 전체 오류(권한 없음, 없는 페이지): 페이지 머리 자리에 제목·설명·[처음으로] 버튼.
- 오류 문구는 무엇이 안 됐는지와 사용자가 할 일을 쓴다. 오류 코드만 보여 주지 않는다.
- 저장 성공 같은 짧은 알림은 화면 아래 토스트로 4초 보여 준다(추정 값). 오류는 저절로 사라지지 않게 한다.

## 6. 할 것 / 하지 말 것

| 할 것 | 하지 말 것 |
|---|---|
| 파랑 하나로 강조 | 버튼마다 다른 색 |
| 모서리 2~4px | 알약 버튼, 둥근 큰 카드 |
| 1px 회색 테두리로 구분 | 카드마다 그림자 |
| 요약 숫자는 테두리 띠 안 칸 | 숫자 카드 4~6장 격자 |
| 상태는 색 + 글자(경과 3일, D-4) | 색 점 하나로만 상태 표시 |
| 버튼·입력 48px | 32px 이하 버튼 |
| 본문 16px, 표 15px | 12px 글자 |
| 표 가로 구분선만 | 세로선·줄무늬 |
| 토큰 이름으로 색 지정 | className에 hex 직접 쓰기 |
| 정부 사이트 같은 정돈된 인상 | 정부 상징·공식 배너 문구 사용 |

## 7. 현재 앱과 연결

### 지금 상태

- 토큰 파일은 `frontend/src/styles/theme.css` 하나다.
- 앱 고유 토큰 11개가 `@theme static`에 있다(`brand`, `line`, `paper`, `danger`, `warn`, `ok` 등).
- shadcn 기본 토큰(`--primary`, `--border`, `--radius` 등)은 `:root`에 있다. 앱은 거의 쓰지 않지만 `--color-border`는 모든 요소의 기본 테두리색이다.
- 글꼴은 Inter + 시스템 글꼴. Pretendard는 불러오지 않는다.
- 회색 글자는 Tailwind 기본 `text-gray-400~900`을 바로 쓴다(약 780곳).
- 모서리는 `rounded`(356곳), `rounded-lg`(214곳), `rounded-xl`(69곳)이 섞여 있다.
- `.dark` 클래스 정의는 있지만 `dark:` 유틸리티 사용은 0건이다. 다크 모드는 사실상 없다.

### 토큰 대응표

| DESIGN.md 토큰 | 기존 변수·클래스 | 기존 값 → 새 값 |
|---|---|---|
| `primary` | `--color-brand` (`bg-brand`) | #1F4E79 → #256ef4 |
| `primary-hover` | `--color-brand-hover` | #173a5c → #0b50d0 |
| `primary-text` | `text-brand` | #1F4E79 → #0b50d0 (새 토큰 `brand-text` 추가 권장) |
| `primary-subtle` | 없음 | 새 토큰 `brand-subtle` 추가 |
| `border` | `--color-line` (`border-line`) | #E5E3DC → #cdd1d5 |
| `divider-light` | `--color-line-soft` | #F0EEE8 → #e6e8ea |
| `surface-subtle` | `--color-paper` (`bg-paper`) | #F8F7F2 → #f4f5f6 (따뜻한 회색 → 중립 회색) |
| `danger-text` | `--color-danger` | #991B1B → #bd2c0f |
| `warning-text` | `--color-warn` | #B45309 → #8a5c00 |
| `success-text` | `--color-ok` | #0F6E56 → #267337 |
| `danger-bg` · `warning-bg` · `success-bg` | `bg-red-50` · `bg-amber-50` · `bg-emerald-50` | Tailwind 기본 → 새 토큰 `danger-bg` 등 |
| `information-*` | 없음 | 새 토큰 추가 |
| `text-basic` | `--ink`, `text-gray-800/900` | #171717 → #1e2124 |
| `text-subtle` | `--body`, `text-gray-600/700` | #4d4d4d → #464c53 |
| `text-muted` | `--mute`, `text-gray-400/500` | #888888 → #6d7882 (`text-gray-400`은 대비 부족이라 모두 이쪽으로) |
| `background` | `--background` (`--canvas-soft`) | #fafafa → #ffffff |
| `surface-inverse` | 없음 (`bg-gray-900`) | 새 토큰 `inverse` 추가 |
| `border` (기본 테두리) | `--border`, `--input` (`--hairline`) | #ebebeb → #cdd1d5 |
| `border-input` | `--input` | #ebebeb → #58616a |
| `focus-ring` | `--ring` | #171717 → #256ef4 |
| `radius.sm` | `--radius` | 0.625rem(10px) → 4px. `rounded-lg`·`rounded-xl` → `rounded`(4px) |
| `radius.xs` | `rounded-sm` | 배지·태그 |
| `typography.font-family` | `body { font-family: Inter… }` | → Pretendard GOV |
| `typography.size.body-sm` | `text-sm`(14px) | 14px → 15px |

### 바꾸는 순서

1. **토큰 교체** — `theme.css`의 `@theme static` 값을 새 값으로 바꾸고, 없는 토큰(`brand-text`, `brand-subtle`, `inverse`, 상태색 bg·border, `information`)을 더한다. `:root`의 shadcn 변수(`--border`, `--input`, `--ring`, `--radius`, `--background`)도 맞춘다. Pretendard GOV를 불러온다. 이름은 그대로 두어 className 수정 없이 색이 먼저 바뀌게 한다.
2. **회색·모서리 정리** — `text-gray-*`를 `text-ink`·`text-body`·`text-mute` 같은 토큰 클래스로, `rounded-lg/xl`을 `rounded`로 일괄 치환한다. 화면별 픽셀 비교로 깨진 곳만 손본다.
3. **공통 컴포넌트** — 버튼·입력·배지·안내 상자·표·탭·요약 띠를 `frontend/src/components/ui/`에 하나씩 만든다. 높이 48px, 모서리 4px을 여기서 고정한다.
4. **공통 틀** — 유틸리티 띠 + 가로 주 메뉴로 바꾼다(`PermitApp.tsx`).
5. **화면** — 대시보드 → 검토 대기(AI 검토) → 허가 목록 → 프로젝트 상세 → 나머지 순. 화면마다 PR 하나.

각 단계는 공개 데모 사용자가 없는 때 머지하고, 단계마다 스크린샷 비교를 남긴다.

### 다크 모드

- 지금은 라이트 모드만 지원한다. 다크 모드는 만들지 않는다.
- 이유: 현재 `dark:` 사용이 0건이고, 공공·보수 기업 사용자에게 우선순위가 낮다.
- `theme.css`의 `.dark` 블록은 토큰 교체 단계에서 지운다(쓰는 곳이 없다).
- 나중에 필요하면 KRDS의 "선명한 화면"(고대비) 모드 토큰(`--krds-color-high-contrast-*`)을 바탕으로 만든다. 그때 본문 대비는 15:1, 제목·라벨은 7:1이 기준이다.
- 새 화면도 색은 반드시 토큰으로 써서, 나중에 모드를 더할 때 토큰만 바꾸면 되게 한다.

## 8. 추정 값 목록

KRDS·Carbon 1차 자료에서 확인하지 못해 정한 값이다. 승인 때 함께 확인한다.

- 터치 최소 크기 44px — KRDS 문서에서 명시값을 찾지 못했다. WCAG 2.5.5를 따랐다.
- 버튼·입력·배지 모서리 2~4px — KRDS 원본은 4~8px. 시안 결정으로 줄였다.
- 포커스 테두리 2px — KRDS 원본은 4px 바깥 그림자.
- 표 행 구분선 `gray-10` — KRDS 표는 `gray-20`.
- 노란 하이라이트 `warning-10`(#ffe0a3) — KRDS에 하이라이트 토큰이 없다.
- 위험(danger) 버튼 모양 — KRDS 버튼에 위험 변형이 없다.
- 유틸리티 띠 40px, 주 메뉴 56px, 선택 밑줄 3px — 시안에서 정한 값.
- 그림자 오프셋·흐림 값 — KRDS는 그림자 색만 정의한다.
- 제목 줄 간격 1.4, 구역 간격 40px, 토스트 4초.

## 9. 출처

- KRDS HTML 컴포넌트 키트(공식 GitHub, 토큰 원본): https://github.com/KRDS-uiux/krds-uiux — `resources/css/token/krds_tokens.css`, `resources/css/common/common.css`, `resources/css/component/component.css` (커밋 d6bb184, 2026-01-12 확인)
- KRDS 스타일 소개: https://www.krds.go.kr/html/site/style/style_01.html
- KRDS 색상(대비 기준, 상태색 규칙): https://www.krds.go.kr/html/site/style/style_02.html
- KRDS 타이포그래피(Pretendard GOV, 본문 최소 16px, AA/AAA 기준): https://www.krds.go.kr/html/site/style/style_03.html
- KRDS 컴포넌트 목록: https://www.krds.go.kr/html/site/component/component_summary.html
- Pretendard GOV(SIL OFL): https://github.com/orioncactus/pretendard/tree/main/packages/pretendard-gov
- Carbon 데이터 표 사용법: https://carbondesignsystem.com/components/data-table/usage/
- Carbon 데이터 표 스타일(행 높이): https://carbondesignsystem.com/components/data-table/style/
- Carbon 상태 표시: https://carbondesignsystem.com/patterns/status-indicator-pattern/
- Carbon 필터: https://carbondesignsystem.com/patterns/filtering/
- WCAG 2.2 대상 크기: https://www.w3.org/WAI/WCAG22/Understanding/target-size-enhanced.html
