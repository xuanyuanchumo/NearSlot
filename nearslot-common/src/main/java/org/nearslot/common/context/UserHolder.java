package org.nearslot.common.context;

/** 当前用户上下文（ThreadLocal 必须在拦截器 afterCompletion 清理，防 Tomcat 线程复用污染）。 */
public final class UserHolder {
    private static final ThreadLocal<Long> LOCAL = new ThreadLocal<>();
    private UserHolder() {}
    public static void set(Long userId) { LOCAL.set(userId); }
    public static Long get() { return LOCAL.get(); }
    public static void clear() { LOCAL.remove(); }
}
