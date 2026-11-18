package org.nearslot.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nearslot.common.context.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 登录校验（order=1）：未登录返回 401 + X-Auth-Reason: no-session。
 *
 * **只读白名单按 HTTP 方法判定**（2026-11-17 审计修复，Blocker）：
 * Spring 的 excludePathPatterns 只匹配路径、不匹配方法，把 `/api/v1/slots` 整体排除
 * 会让 `POST /api/v1/slots` 也免登录（实测可匿名发布时段）。
 * 因此公开只读接口在这里按「GET + 路径前缀」精确放行，写方法一律要求登录。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    /** 允许匿名访问的只读路径（仅 GET）。管理端写接口绝不在此列。 */
    private static final Set<String> PUBLIC_GET_PATHS = Set.of(
        "/api/v1/slots",       // 时段查询：未登录用户需先浏览时段才能决定是否登录
        "/api/v1/tech-rank",   // 热门服务者榜：公开只读（FR-4.3）
        "/api/v1/shop-types",  // 店铺分类字典：公开只读（FR-10），首页筛选要用
        "/api/v1/blogs",       // 笔记详情/热门/店铺笔记：公开只读（FR-7），未登录也应能浏览
        "/api/v1/files",       // 图片读取：笔记配图/店铺封面需匿名可见（FR-11；上传仍要求登录）
        "/api/v1/reviews"      // 技师/店铺评价列表与评分概况：公开只读（FR-12）；提交/撤回/回复要求登录
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("GET".equalsIgnoreCase(request.getMethod()) && isPublicGet(request)) {
            return true;
        }
        if (UserHolder.get() == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            // 精确原因：被顶替(superseded) vs 未登录(no-session)，前端据此决定静默刷新或跳登录
            Object reason = request.getAttribute("nearslot.auth.reason");
            response.setHeader("X-Auth-Reason",
                reason != null ? String.valueOf(reason) : "no-session");
            return false;
        }
        return true;
    }

    /** 前缀匹配（/api/v1/slots 与 /api/v1/slots/xxx 均命中）。 */
    private boolean isPublicGet(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return false;
        for (String path : PUBLIC_GET_PATHS) {
            if (uri.equals(path) || uri.startsWith(path + "/")) return true;
        }
        return false;
    }
}
