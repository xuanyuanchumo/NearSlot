package org.nearslot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 元信息（ADR-018：Knife4j Next 作为接口文档 SSOT）。
 * 访问：/doc.html（Knife4j UI）、/v3/api-docs（JSON 契约，CI 生成入库并 diff）。
 */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI nearSlotOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("NearSlot 预约系统 API")
                .description("时段预约（抢约/Hold/候补/对账）与 AI 助手接口。统一响应 {code,message,data,timestamp}；"
                    + "业务错误码见 BaseCode（501xx 业务 / 100xx Lua / 401xx 认证）。")
                .version("v1")
                .contact(new Contact().name("NearSlot"))
                .license(new License().name("Apache-2.0")))
            .components(new Components()
                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("请求头 authorization: Bearer <AT>（小写头名；AT 为空时不发送）")));
    }
}
