/** 服务商 / 技师 / 时段接口 */
import http from "@/utils/request";
import type { PageResult, Shop, Technician, TimeSlot } from "@/types/api";

export interface ShopListQuery {
  cursor?: string;
  limit?: number;
  /** 业务类目 */
  typeId?: number;
  /** 关键词 */
  keyword?: string;
  /** 纬度 / 经度（GEO 检索） */
  lat?: number;
  lng?: number;
}

/** 附近服务商列表（游标分页） */
export function fetchShops(query: ShopListQuery = {}): Promise<PageResult<Shop>> {
  return http.get<PageResult<Shop>>("/shops", { params: query });
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
  date: string;
  serviceStaffId?: string;
}

/** 指定日期的时段网格 */
export function fetchSlots(shopId: string, query: SlotQuery): Promise<TimeSlot[]> {
  return http.get<TimeSlot[]>(`/shops/${shopId}/slots`, { params: query });
}
