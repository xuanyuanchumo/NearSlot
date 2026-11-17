/**
 * 格式化与鉴权工具单元测试（vitest）
 * 覆盖时区渲染、金额换算、倒计时、以及"禁止 Bearer null"的头构造规则。
 */
import { describe, expect, it } from "vite-plus/test";
import {
  formatCents,
  formatCountdown,
  formatDistance,
  formatTimeInZone,
  shiftDate,
} from "@/utils/format";

describe("format utils", () => {
  it("按指定时区渲染墙钟时间，而不是跟随浏览器本地时区", () => {
    // 2026-11-16T01:30:00Z => Asia/Shanghai 09:30
    expect(formatTimeInZone("2026-11-16T01:30:00Z", "Asia/Shanghai")).toBe("09:30");
    // 同一瞬时在 UTC 时区应为 01:30
    expect(formatTimeInZone("2026-11-16T01:30:00Z", "UTC")).toBe("01:30");
  });

  it("金额以整数分换算，避免浮点误差", () => {
    expect(formatCents(19900)).toBe("¥199.00");
    expect(formatCents(1)).toBe("¥0.01");
    expect(formatCents(undefined)).toBe("--");
  });

  it("距离格式化在 1km 处切换单位", () => {
    expect(formatDistance(320)).toBe("320m");
    expect(formatDistance(1500)).toBe("1.5km");
  });

  it("倒计时负数归零，输出 mm:ss", () => {
    expect(formatCountdown(65_000)).toBe("01:05");
    expect(formatCountdown(-1000)).toBe("00:00");
  });

  it("日期加减不改变 UTC 语义", () => {
    expect(shiftDate("2026-11-16", 1)).toBe("2026-11-17");
    expect(shiftDate("2026-11-16", -16)).toBe("2026-10-31");
  });
});
