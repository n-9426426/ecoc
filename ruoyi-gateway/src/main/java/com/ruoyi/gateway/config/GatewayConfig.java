package com.ruoyi.gateway.config;

import com.ruoyi.gateway.handler.SentinelFallbackHandler;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 网关限流配置
 * 
 * @author ruoyi
 */
@Configuration
public class GatewayConfig
{
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelFallbackHandler sentinelGatewayExceptionHandler()
    {
        return new SentinelFallbackHandler();
    }

    @Bean
    public RouteLocator sseRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                //✅ SSE 专用路由，最小化过滤器
                .route("sse-route", r -> r
                        .path("/vehicle/info/upload/pdf")
                        .and()
                        .header("Accept", "text/event-stream")
                        .filters(f -> f
                                        .stripPrefix(1)
                        )
                        .uri("lb://ruoyi-vehicle")
                )
                .build();
    }
}