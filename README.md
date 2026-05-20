# 🌿 환경기초시설 인허가 관리 시스템

![Status](https://img.shields.io/badge/status-in%20progress-yellow)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue)

> 사기업 엔지니어링 회사의 프로젝트별 환경기초시설 인허가 생애주기를 통합 관리하는 웹 애플리케이션

---

## 👀 목차
1. [📌 프로젝트 개요](#-프로젝트-개요)
2. [📑 요구사항 분석](#-요구사항-분석)
3. [🔧 시스템 아키텍처](#-시스템-아키텍처)
4. [📋 요구사항 명세서](#-요구사항-명세서)
5. [🪧 ERD](#-erd)
6. [🗃️ 테이블 명세서](#️-테이블-명세서)
7. [🎯 API 명세서](#-api-명세서)
8. [🖥 화면 및 기능 설계서](#-화면-및-기능-설계서)
9. [🛠 기술 스택](#-기술-스택)
10. [📁 폴더 구조](#-폴더-구조)
11. [🚀 로컬 실행 방법](#-로컬-실행-방법)
12. [🗓 개발 로드맵](#-개발-로드맵)

---

## 📌 프로젝트 개요

### 📘 프로젝트 소개

**환경기초시설 인허가 관리 시스템**은 민간 엔지니어링 회사가 수행하는 환경기초시설 프로젝트의 인허가 생애주기를 통합 관리하는 웹 애플리케이션입니다.

프로젝트별로 수십 종에 달하는 인허가 항목(설치허가, 가동개시신고, 정기검사, 변경허가, 갱신허가 등)을 한 곳에서 등록·조회·관리하고, 기한 도래 시 자동 알림을 통해 허가 만료 누락을 사전에 방지합니다.

---

### ✅ 배경: 왜 이 서비스가 필요한가?

**반복되는 현장의 문제**

환경기초시설의 인허가는 프로젝트 착공부터 준공 이후까지 수십 종의 절차가 발생합니다. 현장에서는 이를 엑셀·종이문서·담당자 기억에 의존하여 관리하며, 아래 문제가 반복됩니다.

- 허가 만료 및 정기검사 기한 누락
- 담당자 교체 시 인허가 이력 소실
- 문서 분산 보관으로 인한 조회·제출 지연
- 법령 개정 대응 지체로 인한 행정처분 리스크

**프로젝트 기반 통합 관리의 필요성**

인허가는 시설 단위가 아닌 **프로젝트 단위**로 발생합니다. 하나의 프로젝트 안에서 인허가 유형별 진행 현황, 기한, 담당자, 첨부 문서를 한눈에 파악할 수 있는 도구가 필요합니다.

---

## 📑 요구사항 분석

### 🔐 1. 사용자 인증
- 이메일 + 비밀번호 로그인 (JWT)
- Access Token / Refresh Token 재발급
- 권한 구분: 관리자(ADMIN) / 담당자(USER)

### 📂 2. 프로젝트 관리
- 프로젝트 등록·수정·삭제 (프로젝트명, 시설 유형, 위치, 기간, 담당자)
- 프로젝트 상태 관리 (진행중 / 완료 / 보류)
- 프로젝트명·상태·담당자 기준 검색·필터

### 📋 3. 인허가 항목 관리
- 인허가 항목 등록·수정·삭제 (유형, 허가번호, 담당기관, 신청일, 취득일, 만료일)
- 진행 상태 관리 (미신청 / 신청중 / 취득완료 / 만료임박 / 만료)
- 인허가 변경 이력 자동 기록

### 🔔 4. 기한 알림
- 유효기간 만료 D-90 / D-60 / D-30 / D-7 자동 알림
- 시스템 인앱 알림 + 이메일 알림
- 알림 읽음 처리 및 목록 조회

### 📎 5. 문서 관리
- 인허가 항목별 파일 첨부 (허가증, 검사성적서, 신고서 등)
- 지원 형식: PDF / JPG / PNG / XLSX (파일당 최대 10MB)
- AWS S3 저장, 파일 다운로드·삭제

### 📅 6. 캘린더
- 인허가 기한 월별 캘린더 뷰 시각화
- 만료 임박 항목 색상 구분
- 캘린더 항목 클릭 → 인허가 상세 이동

### 📊 7. 대시보드
- 전체 프로젝트 수 / 인허가 항목 수 / 만료 임박 건수 요약
- D-30 이내 만료 임박 인허가 목록
- 인허가 상태별 현황 차트

---

## 🔧 시스템 아키텍처

> <img width="1360" height="980" alt="system_architecture (2)" src="https://github.com/user-attachments/assets/f201d416-23d4-4b76-a97e-1b4865ca6268" />

---

## 📋 요구사항 명세서

> [요구사항 명세서](https://docs.google.com/spreadsheets/d/12-2jKy9jEzSq1YM0xmzFc1EF5N1-gAC9O7Z6JvjYeJ0/edit?hl=ko&gid=1907448249#gid=1907448249)

---

## 🪧 ERD

> 추후 업데이트 예정 (ERDCloud 링크 추가 예정)

---

## 🗃️ 테이블 명세서

> 추후 업데이트 예정

**총 6개 테이블**
`users` `projects` `permit_items` `permit_history` `documents` `notifications`

---

## 🎯 API 명세서

> 추후 업데이트 예정 (Swagger 링크 추가 예정)

---

## 🖥 화면 및 기능 설계서

> 추후 업데이트 예정 (Figma 링크 추가 예정)

---

## 🛠 기술 스택

| 구분 | 기술 |
|------|------|
| Frontend | ![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?logo=typescript&logoColor=white) ![TailwindCSS](https://img.shields.io/badge/TailwindCSS-06B6D4?logo=tailwindcss&logoColor=white) |
| Backend | ![SpringBoot](https://img.shields.io/badge/SpringBoot-3.x-6DB33F?logo=springboot&logoColor=white) ![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white) |
| Database | ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white) |
| 파일 저장 | ![AWS S3](https://img.shields.io/badge/AWS%20S3-569A31?logo=amazons3&logoColor=white) |
| 인프라 | ![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white) ![AWS EC2](https://img.shields.io/badge/AWS%20EC2-FF9900?logo=amazonec2&logoColor=white) |
| 인증 | ![JWT](https://img.shields.io/badge/JWT-000000?logo=jsonwebtokens&logoColor=white) |

---

## 📁 폴더 구조

```
environmental-permit-system/
├── frontend/                   # React + TypeScript
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── api/
│   │   └── types/
│   └── package.json
├── backend/                    # Spring Boot
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/envpermit/
│   │       │       ├── controller/
│   │       │       ├── service/
│   │       │       ├── repository/
│   │       │       └── domain/
│   │       └── resources/
│   │           └── application.yml
│   └── build.gradle
├── docker-compose.yml
└── README.md
```

---

## 🚀 로컬 실행 방법

### 사전 준비
- Java 17+
- Node.js 20+
- Docker Desktop
- PostgreSQL 16 (또는 Docker로 실행)

### 백엔드 실행
```bash
cd backend
./gradlew bootRun
```

### 프론트엔드 실행
```bash
cd frontend
npm install
npm run dev
```

### Docker로 전체 실행
```bash
docker-compose up -d
```

---

## 🗓 개발 로드맵

| 기간 | 작업 내용 | 상태 |
|------|----------|------|
| 1개월차 | 화면 설계 + DB 구조 + 기본 CRUD | 🔄 진행 중 |
| 2개월차 | 기한 알림 로직 + 문서 첨부 + 대시보드 | ⏳ 예정 |
| 3개월차 | 캘린더 뷰 + UI 다듬기 + 배포 | ⏳ 예정 |

---

## 📄 License

[MIT](./LICENSE)
