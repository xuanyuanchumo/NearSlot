package org.nearslot.common.context;

/** 当前用户上下文（拦截器 afterCompletion 必须 clear，防 Tomcat 线程复用污染）。 */
public final class UserHolder {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();
    private static final ThreadLocal<Long> SHOP_ID = new ThreadLocal<>();
    private UserHolder() {}

    public static void set(Long userId) { USER_ID.set(userId); }
    public static Long get() { return USER_ID.get(); }

    /** 角色：CONSUMER / STAFF / MERCHANT / PLATFORM。 */
    public static void setRole(String role) { ROLE.set(role); }
    public static String role() { return ROLE.get(); }
    public static boolean hasRole(String role) { return role != null && role.equals(ROLE.get()); }

    /** 归属店铺（平台管理员为 null，表示可见全部）。 */
    public static void setShopId(Long shopId) { SHOP_ID.set(shopId); }
    public static Long shopId() { return SHOP_ID.get(); }

    public static void clear() {
        USER_ID.remove(); ROLE.remove(); SHOP_ID.remove();
    }
}
