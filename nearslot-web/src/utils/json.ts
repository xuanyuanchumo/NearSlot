/**
 * json-bigint 统一解析入口（雪花 ID 精度保护）
 *
 * 后端 Long 一律字符串化（jackson WRITE_NUMBERS_AS_STRINGS），
 * 前端再用 json-bigint(storeAsString) 兜一层：即使某天后端漏配，也不会把
 * 19 位雪花 ID 解析成丢精度的 Number（docs/standards/frontend.md §2 / ADR-012）。
 */
import JSONBigIntFactory from "json-bigint";

/** 全局唯一实例：storeAsString = true，超出安全整数的 Long 一律转 string */
export const JSONBigInt = JSONBigIntFactory({ storeAsString: true });

/** 安全解析：失败时抛错，由调用方决定降级策略 */
export function parseJsonBig<T = unknown>(text: string): T {
  return JSONBigInt.parse(text) as T;
}

/** 宽松解析：解析失败返回原始字符串（SSE 纯文本帧、非 JSON 响应体） */
export function tryParseJsonBig<T = unknown>(text: string): T | string {
  try {
    return JSONBigInt.parse(text) as T;
  } catch {
    return text;
  }
}
