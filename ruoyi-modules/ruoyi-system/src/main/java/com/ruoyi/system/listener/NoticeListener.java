package com.ruoyi.system.listener;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.ruoyi.system.controller.SysNoticeController;
import com.ruoyi.system.service.ISysNoticeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class NoticeListener {

    private static final Logger log = LoggerFactory.getLogger(NoticeListener.class);

    @Value("${canal.hostname:127.0.0.1}")
    private String jdbcHostname;

    @Value("${canal.port:11111}")
    private Integer jdbcPort;

    @Value("${canal.server:127.0.0.1:11111}")
    private String server;

    @Value("${canal.destination:example}")
    private String destination;

    @Value("${canal.username:}")
    private String jdbcUsername;

    @Value("${canal.password:}")
    private String jdbcPassword;

    @Value("${canal.filter:.*\\..*}")
    private String filter;

    @Value("${canal.max-retry}")
    private long maxRetry;

    @Value("${canal.retry-interval}")
    private long retryInterval;

    @Value("${canal.heartbeat-interval}")
    private long heartbeatInterval;

    @Value("${canal.heartbeat-timeout}")
    private long heartbeatTimeout;

    @Autowired
    private ISysNoticeService noticeService;

    private volatile boolean running = true;
    private CanalConnector connector;
    private volatile long lastHeartbeatTime = System.currentTimeMillis();
    private ScheduledExecutorService heartbeatExecutor;

    @PostConstruct
    public void start() {
        // 启动心跳检测线程
        startHeartbeatMonitor();

        // 启动Canal监听线程
        Thread canalThread = new Thread(this::connectWithRetry, "canal-listener-thread");
        canalThread.setDaemon(true);
        canalThread.start();
    }

    /**
     * 启动心跳监控
     */
    private void startHeartbeatMonitor() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "canal-heartbeat-thread");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                long timeSinceLastHeartbeat = now - lastHeartbeatTime;

                if (timeSinceLastHeartbeat > heartbeatTimeout) {
                    log.warn("Canal 心跳超时 {}ms，触发重连", timeSinceLastHeartbeat);
                    disconnect();
                    lastHeartbeatTime = now; // 重置心跳时间，避免重复触发
                } else {
                    log.debug("Canal 心跳正常，距上次: {}ms", timeSinceLastHeartbeat);
                }
            } catch (Exception e) {
                log.error("心跳检测异常", e);
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    private void connectWithRetry() {
        int retryCount = 0;

        while (running) {
            try {
                log.info("Canal 连接中... 第 {} 次", retryCount + 1);

                connector = CanalConnectors.newSingleConnector(
                        new InetSocketAddress(jdbcHostname, jdbcPort),
                        destination, jdbcUsername, jdbcPassword
                );

                connector.connect();
                connector.subscribe(filter);
                connector.rollback();

                log.info("Canal 连接成功!");
                retryCount = 0;
                lastHeartbeatTime = System.currentTimeMillis(); // 连接成功，更新心跳时间

                startListening();

            } catch (Exception e) {
                retryCount++;
                log.error("Canal 连接失败: {}", e.getMessage());

                disconnect();

                if (retryCount >= maxRetry) {
                    log.warn("Canal 重试次数已达 {} 次，等待更长时间后继续...", maxRetry);
                    retryCount = 0;
                    sleep(retryInterval * 6);
                } else {
                    long waitTime = retryInterval * retryCount;
                    log.info("将在 {} 秒后重试...", waitTime / 1000);
                    sleep(waitTime);
                }
            }
        }
    }

    private void startListening() {
        log.info("开始监听 Canal 消息...");

        while (running) {
            try {
                Message message = connector.getWithoutAck(100);

                // 更新心跳时间（收到消息表示连接正常）
                lastHeartbeatTime = System.currentTimeMillis();

                if (message.getId() != -1 && !message.getEntries().isEmpty()) {
                    for (CanalEntry.Entry entry : message.getEntries()) {
                        if (entry.getEntryType() == CanalEntry.EntryType.ROWDATA) {
                            handleNoticeChange(entry);
                        }
                    }
                    connector.ack(message.getId());
                }

            } catch (Exception e) {
                log.error("Canal 监听异常，准备重连: {}", e.getMessage());
                throw new RuntimeException("Canal 连接断开", e);
            }
        }
    }

    private void handleNoticeChange(CanalEntry.Entry entry) {
        try {
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());

            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                if (rowChange.getEventType() == CanalEntry.EventType.INSERT) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("database", entry.getHeader().getSchemaName());
                    data.put("table", entry.getHeader().getTableName());
                    data.put("eventType", rowChange.getEventType().toString());
                    data.put("timestamp", System.currentTimeMillis());
                    data.put("rows", parseRow(rowData));

                    SysNoticeController.broadcast(data);
                    log.info("新增公告: {}", getColumnValue(rowData.getAfterColumnsList(), "notice_title"));

                } else if (rowChange.getEventType() == CanalEntry.EventType.UPDATE) {
                    log.info("修改公告: {}", getColumnValue(rowData.getAfterColumnsList(), "notice_title"));

                } else if (rowChange.getEventType() == CanalEntry.EventType.DELETE) {
                    log.info("删除公告: {}", getColumnValue(rowData.getBeforeColumnsList(), "notice_title"));
                }
            }
        } catch (Exception e) {
            log.error("处理公告变更异常", e);
        }
    }

    private String getColumnValue(List<CanalEntry.Column> columns, String name) {
        return columns.stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .map(CanalEntry.Column::getValue)
                .orElse("");
    }

    private Map<String, String> parseRow(CanalEntry.RowData rowData) {
        Map<String, String> row = new HashMap<>();
        List<CanalEntry.Column> columns = rowData.getAfterColumnsList();
        columns.forEach(col -> row.put(col.getName(), col.getValue()));
        return row;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("线程被中断");
        }
    }

    private void disconnect() {
        if (connector != null) {
            try {
                connector.disconnect();
            } catch (Exception e) {
                log.warn("断开连接异常: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("停止 Canal 监听...");
        running = false;

        // 关闭心跳线程池
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
            try {
                if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        disconnect();
    }
}