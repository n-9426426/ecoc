package com.ruoyi.gateway.filter;

import com.ruoyi.common.core.constant.CacheConstants;
import com.ruoyi.common.core.constant.HttpStatus;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.constant.TokenConstants;
import com.ruoyi.common.core.utils.JwtUtils;
import com.ruoyi.common.core.utils.ServletUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.redis.service.RedisService;
import com.ruoyi.gateway.config.properties.IgnoreWhiteProperties;
import com.ruoyi.system.api.RemoteTranslateService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关鉴权
 * 
 * @author ruoyi
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered
{
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    // 排除过滤的 uri 地址，nacos自行添加
    @Autowired
    private IgnoreWhiteProperties ignoreWhite;

    @Autowired
    private RedisService redisService;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpRequest.Builder mutate = request.mutate();

        String url = request.getURI().getPath();
        String accept = request.getHeaders().getFirst(HttpHeaders.ACCEPT);

        // SSE 请求放行
        if (StringUtils.isNotNull(accept) && accept.contains("text/event-stream")) {
            log.info("[SSE请求放行] url: {}", url);
            ServerWebExchange mutatedExchange = exchange.mutate()
                    .response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                        @Override
                        public HttpHeaders getHeaders() {
                            HttpHeaders headers = new HttpHeaders();
                            headers.putAll(super.getHeaders());
                            headers.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
                            headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
                            headers.set("X-Accel-Buffering", "no");
                            return headers;
                        }
                    })
                    .build();
            return chain.filter(mutatedExchange);
        }

        // 白名单放行
        if (StringUtils.matches(url, ignoreWhite.getWhites())) {
            return chain.filter(exchange);
        }

        String token = getToken(request);
        if (StringUtils.isEmpty(token)) {
            return translate("login.token.empty", exchange)
                    .flatMap(msg -> unauthorizedResponse(exchange, msg));
        }

        Claims claims = JwtUtils.parseToken(token);
        if (claims == null) {
            return translate("login.token.invalid", exchange)
                    .flatMap(msg -> unauthorizedResponse(exchange, msg));
        }

        String userKey = JwtUtils.getUserKey(claims);
        boolean isLogin = redisService.hasKey(getTokenKey(userKey));
        if (!isLogin) {
            return translate("login.token.expired", exchange)
                    .flatMap(msg -> unauthorizedResponse(exchange, msg));
        }

        String userid = JwtUtils.getUserId(claims);
        String username = JwtUtils.getUserName(claims);
        if (StringUtils.isEmpty(userid) || StringUtils.isEmpty(username)) {
            return translate("login.token.failed", exchange)
                    .flatMap(msg -> unauthorizedResponse(exchange, msg));
        }

        // 设置用户信息到请求头
        addHeader(mutate, SecurityConstants.USER_KEY, userKey);
        addHeader(mutate, SecurityConstants.DETAILS_USER_ID, userid);
        addHeader(mutate, SecurityConstants.DETAILS_USERNAME, username);
        removeHeader(mutate, SecurityConstants.FROM_SOURCE);

        return chain.filter(exchange.mutate().request(mutate.build()).build());
    }

    private void addHeader(ServerHttpRequest.Builder mutate, String name, Object value)
    {
        if (value == null)
        {
            return;
        }
        String valueStr = value.toString();
        String valueEncode = ServletUtils.urlEncode(valueStr);
        mutate.header(name, valueEncode);
    }

    private void removeHeader(ServerHttpRequest.Builder mutate, String name)
    {
        mutate.headers(httpHeaders -> httpHeaders.remove(name)).build();
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String msg)
    {
        log.error("[鉴权异常处理]请求路径:{},错误信息:{}", exchange.getRequest().getPath(), msg);
        return ServletUtils.webFluxResponseWriter(exchange.getResponse(), msg, HttpStatus.UNAUTHORIZED);
    }

    /**
     * 获取缓存key
     */
    private String getTokenKey(String token)
    {
        return CacheConstants.LOGIN_TOKEN_KEY + token;
    }

    /**
     * 获取请求token
     */
    private String getToken(ServerHttpRequest request)
    {
        String token = request.getHeaders().getFirst(SecurityConstants.AUTHORIZATION_HEADER);
        // 如果前端设置了令牌前缀，则裁剪掉前缀
        if (StringUtils.isNotEmpty(token) && token.startsWith(TokenConstants.PREFIX))
        {
            token = token.replaceFirst(TokenConstants.PREFIX, StringUtils.EMPTY);
        }
        return token;
    }

    @Override
    public int getOrder()
    {
        return -200;
    }

    /**
     * 从 ServerWebExchange 解析语言
     */
    private String resolveLang(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        // 1. 从请求参数获取
        String paramLang = request.getQueryParams().getFirst("lang");
        if (StringUtils.isNotEmpty(paramLang)) {
            return paramLang;
        }
        // 2. 从请求头获取
        String headerLang = request.getHeaders().getFirst("Accept-Language");
        if (StringUtils.isNotEmpty(headerLang)) {
            return headerLang;
        }
        // 3. 默认中文
        return "zh_CN";
    }

    private Mono<String> translate(String langKey, ServerWebExchange exchange) {
        return webClientBuilder.build()
                .get()
                .uri("http://ruoyi-system/i18n/translate?langKey={k}&langCode={c}",
                        langKey, resolveLang(exchange))
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn(langKey);
    }
}