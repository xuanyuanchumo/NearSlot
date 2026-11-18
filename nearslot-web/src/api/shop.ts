/** 服务商 / 技师 / 时段接口（契约以 docs/api/design.md §7.5 为准） */
import http from "@/utils/request";
import type { Shop, Technician } from "@/types/api";

/**
 * 后端 GET /api/v1/shops 的实际响应体：{ items, total, page, size }（不是游标分页）。
 * 2026-11-17 审计修复（Blocker）：此前前端按 PageResult{cursor,hasMore} 解析，
 * page.items 拿到 undefined，列表恒空。
 */
export interface ShopPageResponse {
  items: Shop[];
  total: number;
  page: number;
  size: number;
}

export interface ShopListQuery {
  page?: number;
  size?: number;
  /** 业务类目 */
  typeId?: number;
}

/** 服务商列表（后端为 page/size 偏移分页） */
export function fetchShops(query: ShopListQuery = {}): Promise<ShopPageResponse> {
  return http.get<ShopPageResponse>("/shops", { params: query });
}

/** 服务商详情 */
export function fetchShopDetail(shopId: string): Promise<Shop> {
  return http.get<Shop>(`/shops/${shopId}`);
}

/** 服务商技师列表 */
export function fetchTechnicians(shopId: string): Promise<Technician[]> {
  return http.get<Technician[]>(`/shops/${shopId}/technicians`);
}

export interface SlotQuery {
  /** 日期 YYYY-MM-DD（按服务商时区解释） */
  date?: string;
  serviceItemId?: string;
}

/**
 * 时段视图（后端 SlotPublishService#listAvailable 的字段投影）。
 *
 * 2026-11-17 审计修复（Blocker）：后端是 GET /api/v1/slots?shopId=...&date=...，
 * 不是 /shops/{id}/slots（实测 404）；返回的也不是 TimeSlot 实体，
 * 而是含 remaining（实时余量）、没有 priceCents/status 的投影对象。
 */
export interface SlotView {
  id: string;
  shopId: string;
  serviceStaffId: string;
  startsAt: string;
  endsAt: string;
  capacity: number;
  /** 实时余量（Redis 主，DB 兜底） */
  remaining: number;
  minLevel: number;
}

export function fetchSlots(shopId: string, query: SlotQuery = {}): Promise<SlotView[]> {
  return http.get<SlotView[]>("/slots", { params: { shopId, ...query } });
}

/** 附近服务商（GEOSEARCH，半径内按距离升序） */
export function fetchNearbyShops(x: number, y: number, km = 5): Promise<Shop[]> {
  return http.get<Shop[]>("/shops/nearby", { params: { x, y, km } });
}
