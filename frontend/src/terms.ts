// The one place that says what 기한 임박 / 기한 경과 / 일정 지연 mean (2026-11-17 review H5·H6).
// The wording lives in HELP (components/Onboarding.tsx), which also feeds /guide and the "?" tips.
// Backend twins: PermitStatusRule.DUE_SOON_DAYS (만료임박) and ScheduleDates.delayDays (일정 지연).

/** 기한 임박: 완료 전이고 기한까지 이 일수 이내(D-day 포함). */
export const DUE_SOON_DAYS = 30;

/** dDay = due − today in days. */
export const isDueSoon = (dDay: number) => dDay >= 0 && dDay <= DUE_SOON_DAYS;
