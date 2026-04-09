package com.ruoyi.system.config;

import com.ruoyi.system.listener.I18nRefreshListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisListenerConfig {

    /**
     * i18n 刷新消息的 Topic
     */
    public static final String I18N_REFRESH_TOPIC = "i18n:refresh";

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter i18nRefreshListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 订阅 i18n:refresh 频道
        container.addMessageListener(i18nRefreshListenerAdapter,
                new PatternTopic(I18N_REFRESH_TOPIC));
        return container;
    }

    @Bean
    public MessageListenerAdapter i18nRefreshListenerAdapter(I18nRefreshListener listener) {
        // 收到消息后调用 listener 的 onMessage 方法
        return new MessageListenerAdapter(listener, "onMessage");
    }
}