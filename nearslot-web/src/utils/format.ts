/**
 * 格式化工具：时间 / 金额 / 距离
 *
 * 时区铁律（docs/research/11-frontend-ux-deep.md §4.4）：
 * 槽位时间由 startsAt(UTC) + tzId(IANA) 组成，必须用 Intl.DateTimeFormat(tzId) 渲染墙钟时间，
 * 禁止 new Date(ms).toLocaleString()（依赖浏览器本地时区，会与服务端语义不一致）。
 */

/** 把 UTC ISO 字符串按指定 IANA 时区渲染为 HH:mm */
export function formatTimeInZone(iso: string, tzId: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "--:--";
  try {
    return new Intl.DateTimeFormat("zh-CN", {
      timeZone: tzId,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(date);
  } catch {
    return (
      String(date.getHours()).padStart(2, "0") + ":" + String(date.getMinutes()).padStart(2, "0")
    );
  }
}

/** 把 UTC ISO 字符串按指定时区渲染为 YYYY-MM-DD */
export function formatDateInZone(iso: string, tzId: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "----/--/--";
  try {
    const parts = new Intl.DateTimeFormat("en-CA", {
      timeZone: tzId,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).formatToParts(date);
    const pick = (type: string) => parts.find((part) => part.type === type)?.value ?? "";
    return `${pick("year")}-${pick("month")}-${pick("day")}`;
  } catch {
    return date.toISOString().slice(0, 10);
  }
}

/** 人类可读日期：2026-11-16 09:30 */
export function formatDateTimeInZone(iso: string, tzId: string): string {
  return `${formatDateInZone(iso, tzId)} ${formatTimeInZone(iso, tzId)}`;
}

/** 金额：整数分 -> ¥xx.xx */
export function formatCents(cents: number | undefined | null): string {
  if (cents === undefined || cents === null || Number.isNaN(cents)) return "--";
  return `¥${(cents / 100).toFixed(2)}`;
}

/** 距离：米 -> m / km */
export function formatDistance(meters: number | undefined | null): string {
  if (meters === undefined || meters === null || Number.isNaN(meters)) return "距离未知";
  if (meters < 1000) return `${Math.round(meters)}m`;
  return `${(meters / 1000).toFixed(1)}km`;
}

/** 剩余毫秒 -> mm:ss（负数归零），用于 Hold 倒计时 */
export function formatCountdown(remainingMs: number): string {
  const total = Math.max(0, Math.floor(remainingMs / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

/** 生成 YYYY-MM-DD（按指定时区），用于日期选择器默认值 */
export function todayInZone(tzId = "Asia/Shanghai"): string {
  return formatDateInZone(new Date().toISOString(), tzId);
}

/** 在指定日期上加减天数，返回 YYYY-MM-DD */
export function shiftDate(date: string, days: number): string {
  const parsed = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) return date;
  parsed.setUTCDate(parsed.getUTCDate() + days);
  return parsed.toISOString().slice(0, 10);
}
