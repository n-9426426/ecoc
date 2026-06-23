-- MySQL dump 10.13  Distrib 8.0.45, for Win64 (x86_64)
--
-- Host: localhost    Database: ry-config
-- ------------------------------------------------------
-- Server version	8.4.8

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `config_info`
--

DROP TABLE IF EXISTS `config_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `config_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'group_id',
  `content` longtext CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'content',
  `md5` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'md5',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `src_user` text CHARACTER SET utf8mb3 COLLATE utf8mb3_bin COMMENT 'source user',
  `src_ip` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'source ip',
  `app_name` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
  `tenant_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT '' COMMENT '租户字段',
  `c_desc` varchar(256) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'configuration description',
  `c_use` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'configuration usage',
  `effect` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '配置生效的描述',
  `type` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '配置的类型',
  `c_schema` text CHARACTER SET utf8mb3 COLLATE utf8mb3_bin COMMENT '配置的模式',
  `encrypted_data_key` varchar(1024) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL DEFAULT '' COMMENT '密钥',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfo_datagrouptenant` (`data_id`,`group_id`,`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=32 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='config_info';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `config_info`
--

LOCK TABLES `config_info` WRITE;
/*!40000 ALTER TABLE `config_info` DISABLE KEYS */;
INSERT INTO `config_info` VALUES (1,'application-dev.yml','DEFAULT_GROUP','global:\n  # 文件限制\n  multipart:\n    # 单个文件大小\n    maxFileSize: 10MB\n    # 总上传大小\n    maxRequestSize: 20MB\n  redis:\n    host: ruoyi-redis\n    port: 6379\n    password:\n  datasource:\n    druid:\n      # Druid监控页面登录用户名\n      loginUsername: ruoyi\n      # Druid监控页面登录密码\n      loginPassword: 123456\n      # 初始化连接数\n      initial-size: 5\n      # 最小空闲连接数\n      min-idle: 5\n      # 最大活跃连接数\n      maxActive: 300\n      # 获取连接的最大等待时间（毫秒），超时抛异常\n      maxWait: 30000\n      # 建立数据库连接的超时时间（毫秒）\n      connectTimeout: 30000\n      # Socket读取超时时间（毫秒）\n      socketTimeout: 60000\n      # 检测空闲连接的间隔时间（毫秒）\n      timeBetweenEvictionRunsMillis: 60000\n      # 连接保持空闲的最小时间（毫秒），超过则被回收\n      minEvictableIdleTimeMillis: 300000\n      # 验证连接有效性的SQL语句\n      validationQuery: SELECT 1 FROM DUAL\n      # 空闲时检测连接是否有效\n      testWhileIdle: true\n      # 获取连接时不检测（性能优化）\n      testOnBorrow: false\n      # 归还连接时不检测（性能优化）\n      testOnReturn: false\n      # 开启PSCache（PreparedStatement缓存）\n      poolPreparedStatements: true\n      # 每个连接的PSCache大小\n      maxPoolPreparedStatementPerConnectionSize: 20\n      # 启用监控统计（stat）和日志（slf4j）过滤器\n      filters: stat,slf4j\n      # 连接属性配置：合并相同SQL统计，慢SQL阈值5秒\n      connectionProperties: druid.stat.mergeSql\\=true;druid.stat.slowSqlMillis\\=5000\n    # 主库数据源\n    master:\n      driver-class-name: com.mysql.cj.jdbc.Driver\n      hostname: ruoyi-mysql\n      port: 3306\n      username: root\n      password: password\n  springdoc:\n    baseUrl: http://192.168.1.239:8080\n    enabled: true\n  # 监控信息\n  security:\n    name: ruoyi\n    password: 123456\n\nspring:\n  autoconfigure:\n    exclude: com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure\n  cloud:\n    gateway:\n      httpclient:\n        response-timeout: 300s  # SSE 超时时间\n      default-filters:\n        - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE\n        \n# feign 配置\nfeign:\n  sentinel:\n    enabled: true\n  okhttp:\n    enabled: true\n  httpclient:\n    enabled: false\n  client:\n    config:\n      default:\n        connectTimeout: 10000\n        readTimeout: 10000\n  compression:\n    request:\n      enabled: true\n      min-request-size: 8192\n    response:\n      enabled: true\n\n# 暴露监控端点\nmanagement:\n  endpoints:\n    web:\n      exposure:\n        include: \'*\'','7e67cdf833cadb69bf3a36c58c031e64','2020-05-20 12:00:00','2026-06-11 08:35:53',NULL,'172.20.0.1','','','','','','yaml','',''),(2,'ruoyi-gateway-dev.yml','DEFAULT_GROUP','spring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  cloud:\n    gateway:\n      httpclient:\n        response-timeout: 300s\n        pool:\n          type: elastic\n      globalcors:\n        cors-configurations:\n          \'[/**]\':\n            allowedOriginPatterns: \"*\"\n            allowedMethods: \"*\"\n            allowedHeaders: \"*\"\n            allowCredentials: true\n            exposedHeaders: \"Content-Disposition,Content-Type,Cache-Control\"\n      discovery:\n        locator:\n          lowerCaseServiceId: true\n          enabled: true\n      routes:\n        # 认证中心\n        - id: ruoyi-auth\n          uri: lb://ruoyi-auth\n          predicates:\n            - Path=/auth/**\n          filters:\n            # 验证码处理\n            - CacheRequestBody\n            - ValidateCodeFilter\n            - StripPrefix=1\n        # sse单独放行\n        - id: ruoyi-vehicle-sse\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/vehicle/info/upload/pdf\n            - Header=Accept, text/event-stream\n          filters:\n            - StripPrefix=1\n          metadata:\n            response-timeout: 600000\n            connect-timeout: 10000\n        - id: ruoyi-system-sse\n          uri: lb://ruoyi-system\n          predicates:\n            - Path=/system/subscribe/*\n            - Header=Accept, text/event-stream\n          filters:\n            - StripPrefix=1\n          metadata:\n            response-timeout: 600000\n            connect-timeout: 10000 \n        # 系统模块\n        - id: ruoyi-system\n          uri: lb://ruoyi-system\n          predicates:\n            - Path=/system/**\n          filters:\n            - StripPrefix=1\n        # 文件服务\n        - id: ruoyi-file\n          uri: lb://ruoyi-file\n          predicates:\n            - Path=/file/**\n          filters:\n            - StripPrefix=1\n        # 车辆信息服务\n        - id: ruoyi-vehicle\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/vehicle/**\n          filters:\n            - StripPrefix=0\n          metadata:\n            response-timeout: 300000\n            connect-timeout: 10000\n        # xml服务\n        - id: ruoyi-vehicle\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/xml/**\n          filters:\n            - StripPrefix=0\n          metadata:\n            response-timeout: 300000\n            connect-timeout: 10000\n        # 图表服务\n        - id: ruoyi-chart\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/chart/**\n          filters:\n            - StripPrefix=0\n        # 账号管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/account/config/**\n          filters:\n            - StripPrefix=0\n        # 整车物料号管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/material/**\n          filters:\n            - StripPrefix=0\n        # 断点管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/breakpoint/**\n          filters:\n            - StripPrefix=0\n# 安全配置\nsecurity:\n  # 验证码\n  captcha:\n    enabled: false\n    type: math\n  # 防止XSS攻击\n  xss:\n    enabled: true\n    excludeUrls:\n      - /system/notice\n\n  # 不校验白名单\n  ignore:\n    whites:\n      - /auth/logout\n      - /auth/login\n      - /auth/register\n      - /vehicle/info/callback\n      - /vehicle/to-system\n      - /system/i18n/list/all\n      - /*/v2/api-docs\n      - /*/v3/api-docs\n      - /csrf\n      - /swagger-ui/**\n      - /swagger-ui.html\nspringdoc:\n  webjars:\n    prefix:\n  swagger-ui:\n    urls:\n      - name: 系统模块\n        url: /system/v3/api-docs\n      - name: 认证模块\n        url: /auth/v3/api-docs\n      - name: 文件服务\n        url: /file/v3/api-docs\n      - name: 车辆管理服务\n        url: /vehicle/v3/api-docs','1c3e27d4279a2af829bbe8078556f1ef','2020-05-14 14:17:55','2026-05-19 11:25:10','','172.20.0.1','','',NULL,NULL,NULL,'yaml',NULL,''),(3,'ruoyi-auth-dev.yml','DEFAULT_GROUP','spring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}/auth\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'登录模块接口文档\'\n    # 描述\n    description: \'登录模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip','21d181d9d567746f6a84d4c48fa1f1c3','2020-11-20 00:00:00','2026-05-19 11:25:10','','172.20.0.1','','',NULL,NULL,NULL,'yaml',NULL,''),(4,'ruoyi-monitor-dev.yml','DEFAULT_GROUP','# spring\nspring:\n  security:\n    user:\n      name: ${global.security.name}\n      password: ${global.security.password}\n  boot:\n    admin:\n      ui:\n        title: 若依服务状态监控\n','68fb1a0d8e7a13f4841708b68e19b70b','2020-11-20 00:00:00','2026-05-19 11:25:10','','172.20.0.1','','',NULL,NULL,NULL,'yaml',NULL,''),(5,'ruoyi-system-dev.yml','DEFAULT_GROUP','# spring配置\nspring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  datasource:\n    druid:\n      stat-view-servlet:\n        enabled: true\n        loginUsername: ${global.datasource.druid.loginUsername}\n        loginPassword: ${global.datasource.druid.loginPassword}\n    dynamic:\n      druid:\n        initial-size: ${global.datasource.druid.initial-size}\n        min-idle: ${global.datasource.druid.min-idle}\n        maxActive: ${global.datasource.druid.maxActive}\n        maxWait: ${global.datasource.druid.maxWait}\n        connectTimeout: ${global.datasource.druid.connectTimeout}\n        socketTimeout: ${global.datasource.druid.socketTimeout}\n        timeBetweenEvictionRunsMillis: ${global.datasource.druid.timeBetweenEvictionRunsMillis}\n        minEvictableIdleTimeMillis: ${global.datasource.druid.minEvictableIdleTimeMillis}\n        validationQuery: ${global.datasource.druid.validationQuery}\n        testWhileIdle: ${global.datasource.druid.testWhileIdle}\n        testOnBorrow: ${global.datasource.druid.testOnBorrow}\n        testOnReturn: ${global.datasource.druid.testOnReturn}\n        poolPreparedStatements: ${global.datasource.druid.poolPreparedStatements}\n        maxPoolPreparedStatementPerConnectionSize: ${global.datasource.druid.maxPoolPreparedStatementPerConnectionSize}\n        filters: ${global.datasource.druid.filters}\n        connectionProperties: ${global.datasource.druid.connectionProperties}\n      datasource:\n          # 主库数据源\n          master:\n            driver-class-name:  ${global.datasource.master.driver-class-name}\n            hostname: ${global.datasource.master.hostname}\n            post: ${global.datasource.master.port}\n            url: jdbc:mysql://${global.datasource.master.hostname}:${global.datasource.master.port}/ry-cloud?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true\n            username: ${global.datasource.master.username}\n            password: ${global.datasource.master.password}\n          # 从库数据源\n          # slave:\n            # username:\n            # password:\n            # url:\n            # driver-class-name:\n\n# mybatis配置\nmybatis:\n    # 搜索指定包别名\n    typeAliasesPackage: com.ruoyi.system\n    # 配置mapper的扫描，找到所有的mapper.xml映射文件\n    mapperLocations: classpath:mapper/**/*.xml\n\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}/system\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'系统模块接口文档\'\n    # 描述\n    description: \'系统模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip','42ca44517824aa59c3e7f72d9e05fa01','2020-11-20 00:00:00','2026-05-19 11:44:40',NULL,'172.20.0.1','','','','','','yaml','',''),(8,'ruoyi-file-dev.yml','DEFAULT_GROUP','# 本地文件上传    \nfile:\n  domain: http://127.0.0.1:9300\n  path: /home/ruoyi/uploadPath\n  prefix: /statics\n# FastDFS配置\nfdfs:\n  domain: http://127.0.0.1\n  soTimeout: 3000\n  connectTimeout: 2000\n  trackerList: 127.0.0.1:22122\n# Minio配置\nminio:\n  url: http://127.0.0.1:9000\n  accessKey: minioadmin\n  secretKey: minioadmin\n  bucketName: test\n# 防盗链配置\nreferer:\n  # 防盗链开关\n  enabled: false\n  # 允许的域名列表\n  allowed-domains: localhost,127.0.0.1,ruoyi.vip,www.ruoyi.vip','82d32cecd74f36585ca78a8cbe22552b','2020-11-20 00:00:00','2026-05-21 19:39:23',NULL,'172.20.0.1','','','','','','yaml','',''),(9,'sentinel-ruoyi-gateway','DEFAULT_GROUP','[\n    {\n        \"resource\": \"ruoyi-auth\",\n        \"count\": 500,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    },\n	{\n        \"resource\": \"ruoyi-system\",\n        \"count\": 1000,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    },\n    {\n        \"resource\": \"ruoyi-vehicle\",\n        \"count\": 3000,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    }\n]','a8ee1fa35c1dbb63de115d08ae84099a','2020-11-20 00:00:00','2026-05-19 11:25:11','','172.20.0.1','','',NULL,NULL,NULL,'text',NULL,''),(13,'ruoyi-vehicle-dev.yml','DEFAULT_GROUP','# spring配置\nspring:\n  servlet:\n    multipart:\n      # 单个文件大小\n      max-file-size: ${global.multipart.maxFileSize}\n      # 总上传大小\n      max-request-size: ${global.multipart.maxRequestSize}\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  datasource:\n    druid:\n      stat-view-servlet:\n        enabled: true\n        loginUsername: ${global.datasource.druid.loginUsername}\n        loginPassword: ${global.datasource.druid.loginPassword}\n    dynamic:\n      druid:\n        initial-size: ${global.datasource.druid.initial-size}\n        min-idle: ${global.datasource.druid.min-idle}\n        maxActive: ${global.datasource.druid.maxActive}\n        maxWait: ${global.datasource.druid.maxWait}\n        connectTimeout: ${global.datasource.druid.connectTimeout}\n        socketTimeout: ${global.datasource.druid.socketTimeout}\n        timeBetweenEvictionRunsMillis: ${global.datasource.druid.timeBetweenEvictionRunsMillis}\n        minEvictableIdleTimeMillis: ${global.datasource.druid.minEvictableIdleTimeMillis}\n        validationQuery: ${global.datasource.druid.validationQuery}\n        testWhileIdle: ${global.datasource.druid.testWhileIdle}\n        testOnBorrow: ${global.datasource.druid.testOnBorrow}\n        testOnReturn: ${global.datasource.druid.testOnReturn}\n        poolPreparedStatements: ${global.datasource.druid.poolPreparedStatements}\n        maxPoolPreparedStatementPerConnectionSize: ${global.datasource.druid.maxPoolPreparedStatementPerConnectionSize}\n        filters: ${global.datasource.druid.filters}\n        connectionProperties: ${global.datasource.druid.connectionProperties}\n      datasource:\n          # 主库数据源\n          master:\n            driver-class-name:  ${global.datasource.master.driver-class-name}\n            url: jdbc:mysql://${global.datasource.master.hostname}:${global.datasource.master.port}/ry-cloud?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true\n            username: ${global.datasource.master.username}\n            password: ${global.datasource.master.password}\n          # 从库数据源\n          # slave:\n            # username: \n            # password: \n            # url: \n            # driver-class-name: \n\n# mybatis配置\nmybatis:\n    # 搜索指定包别名\n    typeAliasesPackage: com.ruoyi.vehicle\n    # 配置mapper的扫描，找到自己的mapper.xml映射文件\n    mapperLocations: classpath:mapper/**/*.xml\n\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'汽车信息模块接口文档\'\n    # 描述\n    description: \'汽车信息模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip\nocr:\n  python:\n    url: http://localhost:5000/ocr/pdf\n  callback:\n    url: http://localhost:8080','e2e520a46bb71d0083a5c0b0a25848ba','2026-03-30 09:59:54','2026-05-19 11:41:06',NULL,'172.20.0.1','','','','','','yaml','',''),(24,'application-dev.yml','DEFAULT_GROUP','global:\n  # 文件限制\n  multipart:\n    # 单个文件大小\n    maxFileSize: 10MB\n    # 总上传大小\n    maxRequestSize: 20MB\n  redis:\n    host: ruoyi-redis\n    port: 6379\n    password:\n  datasource:\n    druid:\n      # Druid监控页面登录用户名\n      loginUsername: ruoyi\n      # Druid监控页面登录密码\n      loginPassword: 123456\n      # 初始化连接数\n      initial-size: 5\n      # 最小空闲连接数\n      min-idle: 5\n      # 最大活跃连接数\n      maxActive: 300\n      # 获取连接的最大等待时间（毫秒），超时抛异常\n      maxWait: 30000\n      # 建立数据库连接的超时时间（毫秒）\n      connectTimeout: 30000\n      # Socket读取超时时间（毫秒）\n      socketTimeout: 60000\n      # 检测空闲连接的间隔时间（毫秒）\n      timeBetweenEvictionRunsMillis: 60000\n      # 连接保持空闲的最小时间（毫秒），超过则被回收\n      minEvictableIdleTimeMillis: 300000\n      # 验证连接有效性的SQL语句\n      validationQuery: SELECT 1 FROM DUAL\n      # 空闲时检测连接是否有效\n      testWhileIdle: true\n      # 获取连接时不检测（性能优化）\n      testOnBorrow: false\n      # 归还连接时不检测（性能优化）\n      testOnReturn: false\n      # 开启PSCache（PreparedStatement缓存）\n      poolPreparedStatements: true\n      # 每个连接的PSCache大小\n      maxPoolPreparedStatementPerConnectionSize: 20\n      # 启用监控统计（stat）和日志（slf4j）过滤器\n      filters: stat,slf4j\n      # 连接属性配置：合并相同SQL统计，慢SQL阈值5秒\n      connectionProperties: druid.stat.mergeSql\\=true;druid.stat.slowSqlMillis\\=5000\n    # 主库数据源\n    master:\n      driver-class-name: com.mysql.cj.jdbc.Driver\n      hostname: ruoyi-mysql\n      port: 3306\n      username: root\n      password: password\n  springdoc:\n    baseUrl: http://192.168.1.239:8080\n    enabled: false\n  # 监控信息\n  security:\n    name: ruoyi\n    password: 123456\n\nspring:\n  autoconfigure:\n    exclude: com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure\n  cloud:\n    gateway:\n      httpclient:\n        response-timeout: 300s  # SSE 超时时间\n      default-filters:\n        - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE\n        \n# feign 配置\nfeign:\n  sentinel:\n    enabled: true\n  okhttp:\n    enabled: true\n  httpclient:\n    enabled: false\n  client:\n    config:\n      default:\n        connectTimeout: 10000\n        readTimeout: 10000\n  compression:\n    request:\n      enabled: true\n      min-request-size: 8192\n    response:\n      enabled: true\n\n# 暴露监控端点\nmanagement:\n  endpoints:\n    web:\n      exposure:\n        include: \'*\'','1df2f37e6464d52a9bbbc366470d8c08','2026-06-23 09:47:27','2026-06-23 09:48:24',NULL,'172.20.0.1','','172e7c67-00d9-4489-92e9-234aebfc71c0','','','','yaml','',''),(25,'ruoyi-gateway-dev.yml','DEFAULT_GROUP','spring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  cloud:\n    gateway:\n      httpclient:\n        response-timeout: 300s\n        pool:\n          type: elastic\n      globalcors:\n        cors-configurations:\n          \'[/**]\':\n            allowedOriginPatterns: \"*\"\n            allowedMethods: \"*\"\n            allowedHeaders: \"*\"\n            allowCredentials: true\n            exposedHeaders: \"Content-Disposition,Content-Type,Cache-Control\"\n      discovery:\n        locator:\n          lowerCaseServiceId: true\n          enabled: true\n      routes:\n        # 认证中心\n        - id: ruoyi-auth\n          uri: lb://ruoyi-auth\n          predicates:\n            - Path=/auth/**\n          filters:\n            # 验证码处理\n            - CacheRequestBody\n            - ValidateCodeFilter\n            - StripPrefix=1\n        # sse单独放行\n        - id: ruoyi-vehicle-sse\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/vehicle/info/upload/pdf\n            - Header=Accept, text/event-stream\n          filters:\n            - StripPrefix=1\n          metadata:\n            response-timeout: 600000\n            connect-timeout: 10000\n        - id: ruoyi-system-sse\n          uri: lb://ruoyi-system\n          predicates:\n            - Path=/system/subscribe/*\n            - Header=Accept, text/event-stream\n          filters:\n            - StripPrefix=1\n          metadata:\n            response-timeout: 600000\n            connect-timeout: 10000 \n        # 系统模块\n        - id: ruoyi-system\n          uri: lb://ruoyi-system\n          predicates:\n            - Path=/system/**\n          filters:\n            - StripPrefix=1\n        # 文件服务\n        - id: ruoyi-file\n          uri: lb://ruoyi-file\n          predicates:\n            - Path=/file/**\n          filters:\n            - StripPrefix=1\n        # 车辆信息服务\n        - id: ruoyi-vehicle\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/vehicle/**\n          filters:\n            - StripPrefix=0\n          metadata:\n            response-timeout: 300000\n            connect-timeout: 10000\n        # xml服务\n        - id: ruoyi-vehicle\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/xml/**\n          filters:\n            - StripPrefix=0\n          metadata:\n            response-timeout: 300000\n            connect-timeout: 10000\n        # 图表服务\n        - id: ruoyi-chart\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/chart/**\n          filters:\n            - StripPrefix=0\n        # 账号管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/account/config/**\n          filters:\n            - StripPrefix=0\n        # 整车物料号管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/material/**\n          filters:\n            - StripPrefix=0\n        # 断点管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/breakpoint/**\n          filters:\n            - StripPrefix=0\n# 安全配置\nsecurity:\n  # 验证码\n  captcha:\n    enabled: false\n    type: math\n  # 防止XSS攻击\n  xss:\n    enabled: true\n    excludeUrls:\n      - /system/notice\n\n  # 不校验白名单\n  ignore:\n    whites:\n      - /auth/logout\n      - /auth/login\n      - /auth/register\n      - /vehicle/info/callback\n      - /vehicle/to-system\n      - /system/i18n/list/all\n      - /*/v2/api-docs\n      - /*/v3/api-docs\n      - /csrf\n      - /swagger-ui/**\n      - /swagger-ui.html\nspringdoc:\n  webjars:\n    prefix:\n  swagger-ui:\n    urls:\n      - name: 系统模块\n        url: /system/v3/api-docs\n      - name: 认证模块\n        url: /auth/v3/api-docs\n      - name: 文件服务\n        url: /file/v3/api-docs\n      - name: 车辆管理服务\n        url: /vehicle/v3/api-docs','1c3e27d4279a2af829bbe8078556f1ef','2026-06-23 09:47:27','2026-06-23 09:47:27',NULL,'172.20.0.1','','172e7c67-00d9-4489-92e9-234aebfc71c0',NULL,NULL,NULL,'yaml',NULL,''),(26,'ruoyi-auth-dev.yml','DEFAULT_GROUP','spring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}/auth\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'登录模块接口文档\'\n    # 描述\n    description: \'登录模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip','21d181d9d567746f6a84d4c48fa1f1c3','2026-06-23 09:47:27','2026-06-23 09:47:27',NULL,'172.20.0.1','','172e7c67-00d9-4489-92e9-234aebfc71c0',NULL,NULL,NULL,'yaml',NULL,''),(27,'ruoyi-monitor-dev.yml','DEFAULT_GROUP','# spring\nspring:\n  security:\n    user:\n      name: ${global.security.name}\n      password: ${global.security.password}\n  boot:\n    admin:\n      ui:\n        title: 若依服务状态监控\n','68fb1a0d8e7a13f4841708b68e19b70b','2026-06-23 09:47:27','2026-06-23 09:47:27',NULL,'172.20.0.1','','172e7c67-00d9-4489-92e9-234aebfc71c0',NULL,NULL,NULL,'yaml',NULL,''),(28,'ruoyi-system-dev.yml','DEFAULT_GROUP','# spring配置\nspring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  datasource:\n    druid:\n      stat-view-servlet:\n        enabled: true\n        loginUsername: ${global.datasource.druid.loginUsername}\n        loginPassword: ${global.datasource.druid.loginPassword}\n    dynamic:\n      druid:\n        initial-size: ${global.datasource.druid.initial-size}\n        min-idle: ${global.datasource.druid.min-idle}\n        maxActive: ${global.datasource.druid.maxActive}\n        maxWait: ${global.datasource.druid.maxWait}\n        connectTimeout: ${global.datasource.druid.connectTimeout}\n        socketTimeout: ${global.datasource.druid.socketTimeout}\n        timeBetweenEvictionRunsMillis: ${global.datasource.druid.timeBetweenEvictionRunsMillis}\n        minEvictableIdleTimeMillis: ${global.datasource.druid.minEvictableIdleTimeMillis}\n        validationQuery: ${global.datasource.druid.validationQuery}\n        testWhileIdle: ${global.datasource.druid.testWhileIdle}\n        testOnBorrow: ${global.datasource.druid.testOnBorrow}\n        testOnReturn: ${global.datasource.druid.testOnReturn}\n        poolPreparedStatements: ${global.datasource.druid.poolPreparedStatements}\n        maxPoolPreparedStatementPerConnectionSize: ${global.datasource.druid.maxPoolPreparedStatementPerConnectionSize}\n        filters: ${global.datasource.druid.filters}\n        connectionProperties: ${global.datasource.druid.connectionProperties}\n      datasource:\n          # 主库数据源\n          master:\n            driver-class-name:  ${global.datasource.master.driver-class-name}\n            hostname: ${global.datasource.master.hostname}\n            post: ${global.datasource.master.port}\n            url: jdbc:mysql://${global.datasource.master.hostname}:${global.datasource.master.port}/ecoc?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true\n            username: ${global.datasource.master.username}\n            password: ${global.datasource.master.password}\n          # 从库数据源\n          # slave:\n            # username:\n            # password:\n            # url:\n            # driver-class-name:\n\n# mybatis配置\nmybatis:\n    # 搜索指定包别名\n    typeAliasesPackage: com.ruoyi.system\n    # 配置mapper的扫描，找到所有的mapper.xml映射文件\n    mapperLocations: classpath:mapper/**/*.xml\n\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}/system\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'系统模块接口文档\'\n    # 描述\n    description: \'系统模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip','03dfed599e27feaf467310afc8a4c014','2026-06-23 09:47:27','2026-06-23 09:49:26',NULL,'172.20.0.1','','172e7c67-00d9-4489-92e9-234aebfc71c0','','','','yaml','',''),(29,'ruoyi-file-dev.yml','DEFAULT_GROUP','# 本地文件上传    \nfile:\n  domain: http://127.0.0.1:9300\n  path: /home/ruoyi/uploadPath\n  prefix: /statics\n# FastDFS配置\nfdfs:\n  domain: http://127.0.0.1\n  soTimeout: 3000\n  connectTimeout: 2000\n  trackerList: 127.0.0.1:22122\n# Minio配置\nminio:\n  url: http://127.0.0.1:9000\n  accessKey: minioadmin\n  secretKey: minioadmin\n  bucketName: test\n# 防盗链配置\nreferer:\n  # 防盗链开关\n  enabled: false\n  # 允许的域名列表\n  allowed-domains: localhost,127.0.0.1,ruoyi.vip,www.ruoyi.vip','82d32cecd74f36585ca78a8cbe22552b','2026-06-23 09:47:27','2026-06-23 09:47:27',NULL,'172.20.0.1','','172e7c67-00d9-4489-92e9-234aebfc71c0','',NULL,NULL,'yaml',NULL,''),(30,'sentinel-ruoyi-gateway','DEFAULT_GROUP','[\n    {\n        \"resource\": \"ruoyi-auth\",\n        \"count\": 500,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    },\n	{\n        \"resource\": \"ruoyi-system\",\n        \"count\": 1000,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    },\n    {\n        \"resource\": \"ruoyi-vehicle\",\n        \"count\": 3000,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    }\n]','a8ee1fa35c1dbb63de115d08ae84099a','2026-06-23 09:47:27','2026-06-23 09:47:27',NULL,'172.20.0.1','','172e7c67-00d9-4489-92e9-234aebfc71c0',NULL,NULL,NULL,'text',NULL,''),(31,'ruoyi-vehicle-dev.yml','DEFAULT_GROUP','# spring配置\nspring:\n  servlet:\n    multipart:\n      # 单个文件大小\n      max-file-size: ${global.multipart.maxFileSize}\n      # 总上传大小\n      max-request-size: ${global.multipart.maxRequestSize}\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  datasource:\n    druid:\n      stat-view-servlet:\n        enabled: true\n        loginUsername: ${global.datasource.druid.loginUsername}\n        loginPassword: ${global.datasource.druid.loginPassword}\n    dynamic:\n      druid:\n        initial-size: ${global.datasource.druid.initial-size}\n        min-idle: ${global.datasource.druid.min-idle}\n        maxActive: ${global.datasource.druid.maxActive}\n        maxWait: ${global.datasource.druid.maxWait}\n        connectTimeout: ${global.datasource.druid.connectTimeout}\n        socketTimeout: ${global.datasource.druid.socketTimeout}\n        timeBetweenEvictionRunsMillis: ${global.datasource.druid.timeBetweenEvictionRunsMillis}\n        minEvictableIdleTimeMillis: ${global.datasource.druid.minEvictableIdleTimeMillis}\n        validationQuery: ${global.datasource.druid.validationQuery}\n        testWhileIdle: ${global.datasource.druid.testWhileIdle}\n        testOnBorrow: ${global.datasource.druid.testOnBorrow}\n        testOnReturn: ${global.datasource.druid.testOnReturn}\n        poolPreparedStatements: ${global.datasource.druid.poolPreparedStatements}\n        maxPoolPreparedStatementPerConnectionSize: ${global.datasource.druid.maxPoolPreparedStatementPerConnectionSize}\n        filters: ${global.datasource.druid.filters}\n        connectionProperties: ${global.datasource.druid.connectionProperties}\n      datasource:\n          # 主库数据源\n          master:\n            driver-class-name:  ${global.datasource.master.driver-class-name}\n            url: jdbc:mysql://${global.datasource.master.hostname}:${global.datasource.master.port}/ecoc?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true\n            username: ${global.datasource.master.username}\n            password: ${global.datasource.master.password}\n          # 从库数据源\n          # slave:\n            # username: \n            # password: \n            # url: \n            # driver-class-name: \n\n# mybatis配置\nmybatis:\n    # 搜索指定包别名\n    typeAliasesPackage: com.ruoyi.vehicle\n    # 配置mapper的扫描，找到自己的mapper.xml映射文件\n    mapperLocations: classpath:mapper/**/*.xml\n\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'汽车信息模块接口文档\'\n    # 描述\n    description: \'汽车信息模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip\nocr:\n  python:\n    url: http://localhost:5000/ocr/pdf\n  callback:\n    url: http://localhost:8080','45eb4e28fcdedb38a6ec7024a6022ce4','2026-06-23 09:47:27','2026-06-23 09:49:54',NULL,'172.20.0.1','','172e7c67-00d9-4489-92e9-234aebfc71c0','','','','yaml','','');
/*!40000 ALTER TABLE `config_info` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `config_info_aggr`
--

DROP TABLE IF EXISTS `config_info_aggr`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `config_info_aggr` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
  `group_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
  `datum_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'datum_id',
  `content` longtext CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT '内容',
  `gmt_modified` datetime NOT NULL COMMENT '修改时间',
  `app_name` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL,
  `tenant_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT '' COMMENT '租户字段',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfoaggr_datagrouptenantdatum` (`data_id`,`group_id`,`tenant_id`,`datum_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='增加租户字段';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `config_info_aggr`
--

LOCK TABLES `config_info_aggr` WRITE;
/*!40000 ALTER TABLE `config_info_aggr` DISABLE KEYS */;
/*!40000 ALTER TABLE `config_info_aggr` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `config_info_beta`
--

DROP TABLE IF EXISTS `config_info_beta`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `config_info_beta` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
  `app_name` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
  `content` longtext CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'content',
  `beta_ips` varchar(1024) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'betaIps',
  `md5` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'md5',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `src_user` text CHARACTER SET utf8mb3 COLLATE utf8mb3_bin COMMENT 'source user',
  `src_ip` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'source ip',
  `tenant_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT '' COMMENT '租户字段',
  `encrypted_data_key` varchar(1024) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL DEFAULT '' COMMENT '密钥',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfobeta_datagrouptenant` (`data_id`,`group_id`,`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='config_info_beta';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `config_info_beta`
--

LOCK TABLES `config_info_beta` WRITE;
/*!40000 ALTER TABLE `config_info_beta` DISABLE KEYS */;
/*!40000 ALTER TABLE `config_info_beta` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `config_info_gray`
--

DROP TABLE IF EXISTS `config_info_gray`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `config_info_gray` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) NOT NULL COMMENT 'group_id',
  `content` longtext NOT NULL COMMENT 'content',
  `md5` varchar(32) DEFAULT NULL COMMENT 'md5',
  `src_user` text COMMENT 'src_user',
  `src_ip` varchar(100) DEFAULT NULL COMMENT 'src_ip',
  `gmt_create` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'gmt_create',
  `gmt_modified` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'gmt_modified',
  `app_name` varchar(128) DEFAULT NULL COMMENT 'app_name',
  `tenant_id` varchar(128) DEFAULT '' COMMENT 'tenant_id',
  `gray_name` varchar(128) NOT NULL COMMENT 'gray_name',
  `gray_rule` text NOT NULL COMMENT 'gray_rule',
  `encrypted_data_key` varchar(256) NOT NULL DEFAULT '' COMMENT 'encrypted_data_key',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfogray_datagrouptenantgray` (`data_id`,`group_id`,`tenant_id`,`gray_name`),
  KEY `idx_dataid_gmt_modified` (`data_id`,`gmt_modified`),
  KEY `idx_gmt_modified` (`gmt_modified`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='config_info_gray';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `config_info_gray`
--

LOCK TABLES `config_info_gray` WRITE;
/*!40000 ALTER TABLE `config_info_gray` DISABLE KEYS */;
/*!40000 ALTER TABLE `config_info_gray` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `config_info_tag`
--

DROP TABLE IF EXISTS `config_info_tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `config_info_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
  `tenant_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT '' COMMENT 'tenant_id',
  `tag_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'tag_id',
  `app_name` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
  `content` longtext CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'content',
  `md5` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'md5',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `src_user` text CHARACTER SET utf8mb3 COLLATE utf8mb3_bin COMMENT 'source user',
  `src_ip` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'source ip',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfotag_datagrouptenanttag` (`data_id`,`group_id`,`tenant_id`,`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='config_info_tag';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `config_info_tag`
--

LOCK TABLES `config_info_tag` WRITE;
/*!40000 ALTER TABLE `config_info_tag` DISABLE KEYS */;
/*!40000 ALTER TABLE `config_info_tag` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `config_tags_relation`
--

DROP TABLE IF EXISTS `config_tags_relation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `config_tags_relation` (
  `id` bigint NOT NULL COMMENT 'id',
  `tag_name` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'tag_name',
  `tag_type` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'tag_type',
  `data_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
  `tenant_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT '' COMMENT 'tenant_id',
  `nid` bigint NOT NULL AUTO_INCREMENT COMMENT 'nid, 自增长标识',
  PRIMARY KEY (`nid`),
  UNIQUE KEY `uk_configtagrelation_configidtag` (`id`,`tag_name`,`tag_type`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='config_tag_relation';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `config_tags_relation`
--

LOCK TABLES `config_tags_relation` WRITE;
/*!40000 ALTER TABLE `config_tags_relation` DISABLE KEYS */;
/*!40000 ALTER TABLE `config_tags_relation` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `group_capacity`
--

DROP TABLE IF EXISTS `group_capacity`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_capacity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `group_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL DEFAULT '' COMMENT 'Group ID，空字符表示整个集群',
  `quota` int unsigned NOT NULL DEFAULT '0' COMMENT '配额，0表示使用默认值',
  `usage` int unsigned NOT NULL DEFAULT '0' COMMENT '使用量',
  `max_size` int unsigned NOT NULL DEFAULT '0' COMMENT '单个配置大小上限，单位为字节，0表示使用默认值',
  `max_aggr_count` int unsigned NOT NULL DEFAULT '0' COMMENT '聚合子配置最大个数，，0表示使用默认值',
  `max_aggr_size` int unsigned NOT NULL DEFAULT '0' COMMENT '单个聚合数据的子配置大小上限，单位为字节，0表示使用默认值',
  `max_history_count` int unsigned NOT NULL DEFAULT '0' COMMENT '最大变更历史数量',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_id` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='集群、各Group容量信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `group_capacity`
--

LOCK TABLES `group_capacity` WRITE;
/*!40000 ALTER TABLE `group_capacity` DISABLE KEYS */;
/*!40000 ALTER TABLE `group_capacity` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `his_config_info`
--

DROP TABLE IF EXISTS `his_config_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_config_info` (
  `id` bigint unsigned NOT NULL COMMENT 'id',
  `nid` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'nid, 自增标识',
  `data_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'group_id',
  `app_name` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'app_name',
  `content` longtext CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'content',
  `md5` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'md5',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `src_user` text CHARACTER SET utf8mb3 COLLATE utf8mb3_bin COMMENT 'source user',
  `src_ip` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'source ip',
  `op_type` char(10) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'operation type',
  `tenant_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT '' COMMENT '租户字段',
  `encrypted_data_key` varchar(1024) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL DEFAULT '' COMMENT '密钥',
  `publish_type` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT 'formal' COMMENT 'publish type gray or formal',
  `gray_name` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'gray name',
  `ext_info` longtext CHARACTER SET utf8mb3 COLLATE utf8mb3_bin COMMENT 'ext info',
  PRIMARY KEY (`nid`),
  KEY `idx_gmt_create` (`gmt_create`),
  KEY `idx_gmt_modified` (`gmt_modified`),
  KEY `idx_did` (`data_id`)
) ENGINE=InnoDB AUTO_INCREMENT=212 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='多租户改造';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `his_config_info`
--

LOCK TABLES `his_config_info` WRITE;
/*!40000 ALTER TABLE `his_config_info` DISABLE KEYS */;
INSERT INTO `his_config_info` VALUES (1,200,'application-dev.yml','DEFAULT_GROUP','','global:\n  # 文件限制\n  multipart:\n    # 单个文件大小\n    maxFileSize: 10MB\n    # 总上传大小\n    maxRequestSize: 20MB\n  redis:\n    host: ruoyi-redis\n    port: 6379\n    password:\n  datasource:\n    druid:\n      # Druid监控页面登录用户名\n      loginUsername: ruoyi\n      # Druid监控页面登录密码\n      loginPassword: 123456\n      # 初始化连接数\n      initial-size: 5\n      # 最小空闲连接数\n      min-idle: 5\n      # 最大活跃连接数\n      maxActive: 300\n      # 获取连接的最大等待时间（毫秒），超时抛异常\n      maxWait: 30000\n      # 建立数据库连接的超时时间（毫秒）\n      connectTimeout: 30000\n      # Socket读取超时时间（毫秒）\n      socketTimeout: 60000\n      # 检测空闲连接的间隔时间（毫秒）\n      timeBetweenEvictionRunsMillis: 60000\n      # 连接保持空闲的最小时间（毫秒），超过则被回收\n      minEvictableIdleTimeMillis: 300000\n      # 验证连接有效性的SQL语句\n      validationQuery: SELECT 1 FROM DUAL\n      # 空闲时检测连接是否有效\n      testWhileIdle: true\n      # 获取连接时不检测（性能优化）\n      testOnBorrow: false\n      # 归还连接时不检测（性能优化）\n      testOnReturn: false\n      # 开启PSCache（PreparedStatement缓存）\n      poolPreparedStatements: true\n      # 每个连接的PSCache大小\n      maxPoolPreparedStatementPerConnectionSize: 20\n      # 启用监控统计（stat）和日志（slf4j）过滤器\n      filters: stat,slf4j\n      # 连接属性配置：合并相同SQL统计，慢SQL阈值5秒\n      connectionProperties: druid.stat.mergeSql\\=true;druid.stat.slowSqlMillis\\=5000\n    # 主库数据源\n    master:\n      driver-class-name: com.mysql.cj.jdbc.Driver\n      hostname: ruoyi-mysql\n      port: 3306\n      username: root\n      password: password\n  springdoc:\n    baseUrl: http://192.168.1.126:8080\n    enabled: true\n  # 监控信息\n  security:\n    name: ruoyi\n    password: 123456\n\nspring:\n  autoconfigure:\n    exclude: com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure\n  cloud:\n    gateway:\n      httpclient:\n        response-timeout: 300s  # SSE 超时时间\n      default-filters:\n        - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE\n        \n# feign 配置\nfeign:\n  sentinel:\n    enabled: true\n  okhttp:\n    enabled: true\n  httpclient:\n    enabled: false\n  client:\n    config:\n      default:\n        connectTimeout: 10000\n        readTimeout: 10000\n  compression:\n    request:\n      enabled: true\n      min-request-size: 8192\n    response:\n      enabled: true\n\n# 暴露监控端点\nmanagement:\n  endpoints:\n    web:\n      exposure:\n        include: \'*\'','a35cd724c69587acfb25b369259fb588','2026-06-11 08:35:52','2026-06-11 00:35:53',NULL,'172.20.0.1','U','','','formal',NULL,NULL),(0,201,'application-dev.yml','DEFAULT_GROUP','','global:\n  # 文件限制\n  multipart:\n    # 单个文件大小\n    maxFileSize: 10MB\n    # 总上传大小\n    maxRequestSize: 20MB\n  redis:\n    host: ruoyi-redis\n    port: 6379\n    password:\n  datasource:\n    druid:\n      # Druid监控页面登录用户名\n      loginUsername: ruoyi\n      # Druid监控页面登录密码\n      loginPassword: 123456\n      # 初始化连接数\n      initial-size: 5\n      # 最小空闲连接数\n      min-idle: 5\n      # 最大活跃连接数\n      maxActive: 300\n      # 获取连接的最大等待时间（毫秒），超时抛异常\n      maxWait: 30000\n      # 建立数据库连接的超时时间（毫秒）\n      connectTimeout: 30000\n      # Socket读取超时时间（毫秒）\n      socketTimeout: 60000\n      # 检测空闲连接的间隔时间（毫秒）\n      timeBetweenEvictionRunsMillis: 60000\n      # 连接保持空闲的最小时间（毫秒），超过则被回收\n      minEvictableIdleTimeMillis: 300000\n      # 验证连接有效性的SQL语句\n      validationQuery: SELECT 1 FROM DUAL\n      # 空闲时检测连接是否有效\n      testWhileIdle: true\n      # 获取连接时不检测（性能优化）\n      testOnBorrow: false\n      # 归还连接时不检测（性能优化）\n      testOnReturn: false\n      # 开启PSCache（PreparedStatement缓存）\n      poolPreparedStatements: true\n      # 每个连接的PSCache大小\n      maxPoolPreparedStatementPerConnectionSize: 20\n      # 启用监控统计（stat）和日志（slf4j）过滤器\n      filters: stat,slf4j\n      # 连接属性配置：合并相同SQL统计，慢SQL阈值5秒\n      connectionProperties: druid.stat.mergeSql\\=true;druid.stat.slowSqlMillis\\=5000\n    # 主库数据源\n    master:\n      driver-class-name: com.mysql.cj.jdbc.Driver\n      hostname: ruoyi-mysql\n      port: 3306\n      username: root\n      password: password\n  springdoc:\n    baseUrl: http://192.168.1.239:8080\n    enabled: true\n  # 监控信息\n  security:\n    name: ruoyi\n    password: 123456\n\nspring:\n  autoconfigure:\n    exclude: com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure\n  cloud:\n    gateway:\n      httpclient:\n        response-timeout: 300s  # SSE 超时时间\n      default-filters:\n        - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE\n        \n# feign 配置\nfeign:\n  sentinel:\n    enabled: true\n  okhttp:\n    enabled: true\n  httpclient:\n    enabled: false\n  client:\n    config:\n      default:\n        connectTimeout: 10000\n        readTimeout: 10000\n  compression:\n    request:\n      enabled: true\n      min-request-size: 8192\n    response:\n      enabled: true\n\n# 暴露监控端点\nmanagement:\n  endpoints:\n    web:\n      exposure:\n        include: \'*\'','7e67cdf833cadb69bf3a36c58c031e64','2026-06-23 09:47:26','2026-06-23 01:47:27',NULL,'172.20.0.1','I','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(0,202,'ruoyi-gateway-dev.yml','DEFAULT_GROUP','','spring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  cloud:\n    gateway:\n      httpclient:\n        response-timeout: 300s\n        pool:\n          type: elastic\n      globalcors:\n        cors-configurations:\n          \'[/**]\':\n            allowedOriginPatterns: \"*\"\n            allowedMethods: \"*\"\n            allowedHeaders: \"*\"\n            allowCredentials: true\n            exposedHeaders: \"Content-Disposition,Content-Type,Cache-Control\"\n      discovery:\n        locator:\n          lowerCaseServiceId: true\n          enabled: true\n      routes:\n        # 认证中心\n        - id: ruoyi-auth\n          uri: lb://ruoyi-auth\n          predicates:\n            - Path=/auth/**\n          filters:\n            # 验证码处理\n            - CacheRequestBody\n            - ValidateCodeFilter\n            - StripPrefix=1\n        # sse单独放行\n        - id: ruoyi-vehicle-sse\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/vehicle/info/upload/pdf\n            - Header=Accept, text/event-stream\n          filters:\n            - StripPrefix=1\n          metadata:\n            response-timeout: 600000\n            connect-timeout: 10000\n        - id: ruoyi-system-sse\n          uri: lb://ruoyi-system\n          predicates:\n            - Path=/system/subscribe/*\n            - Header=Accept, text/event-stream\n          filters:\n            - StripPrefix=1\n          metadata:\n            response-timeout: 600000\n            connect-timeout: 10000 \n        # 系统模块\n        - id: ruoyi-system\n          uri: lb://ruoyi-system\n          predicates:\n            - Path=/system/**\n          filters:\n            - StripPrefix=1\n        # 文件服务\n        - id: ruoyi-file\n          uri: lb://ruoyi-file\n          predicates:\n            - Path=/file/**\n          filters:\n            - StripPrefix=1\n        # 车辆信息服务\n        - id: ruoyi-vehicle\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/vehicle/**\n          filters:\n            - StripPrefix=0\n          metadata:\n            response-timeout: 300000\n            connect-timeout: 10000\n        # xml服务\n        - id: ruoyi-vehicle\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/xml/**\n          filters:\n            - StripPrefix=0\n          metadata:\n            response-timeout: 300000\n            connect-timeout: 10000\n        # 图表服务\n        - id: ruoyi-chart\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/chart/**\n          filters:\n            - StripPrefix=0\n        # 账号管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/account/config/**\n          filters:\n            - StripPrefix=0\n        # 整车物料号管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/material/**\n          filters:\n            - StripPrefix=0\n        # 断点管理\n        - id: ruoyi-account\n          uri: lb://ruoyi-vehicle\n          predicates:\n            - Path=/breakpoint/**\n          filters:\n            - StripPrefix=0\n# 安全配置\nsecurity:\n  # 验证码\n  captcha:\n    enabled: false\n    type: math\n  # 防止XSS攻击\n  xss:\n    enabled: true\n    excludeUrls:\n      - /system/notice\n\n  # 不校验白名单\n  ignore:\n    whites:\n      - /auth/logout\n      - /auth/login\n      - /auth/register\n      - /vehicle/info/callback\n      - /vehicle/to-system\n      - /system/i18n/list/all\n      - /*/v2/api-docs\n      - /*/v3/api-docs\n      - /csrf\n      - /swagger-ui/**\n      - /swagger-ui.html\nspringdoc:\n  webjars:\n    prefix:\n  swagger-ui:\n    urls:\n      - name: 系统模块\n        url: /system/v3/api-docs\n      - name: 认证模块\n        url: /auth/v3/api-docs\n      - name: 文件服务\n        url: /file/v3/api-docs\n      - name: 车辆管理服务\n        url: /vehicle/v3/api-docs','1c3e27d4279a2af829bbe8078556f1ef','2026-06-23 09:47:26','2026-06-23 01:47:27',NULL,'172.20.0.1','I','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(0,203,'ruoyi-auth-dev.yml','DEFAULT_GROUP','','spring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}/auth\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'登录模块接口文档\'\n    # 描述\n    description: \'登录模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip','21d181d9d567746f6a84d4c48fa1f1c3','2026-06-23 09:47:26','2026-06-23 01:47:27',NULL,'172.20.0.1','I','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(0,204,'ruoyi-monitor-dev.yml','DEFAULT_GROUP','','# spring\nspring:\n  security:\n    user:\n      name: ${global.security.name}\n      password: ${global.security.password}\n  boot:\n    admin:\n      ui:\n        title: 若依服务状态监控\n','68fb1a0d8e7a13f4841708b68e19b70b','2026-06-23 09:47:26','2026-06-23 01:47:27',NULL,'172.20.0.1','I','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(0,205,'ruoyi-system-dev.yml','DEFAULT_GROUP','','# spring配置\nspring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  datasource:\n    druid:\n      stat-view-servlet:\n        enabled: true\n        loginUsername: ${global.datasource.druid.loginUsername}\n        loginPassword: ${global.datasource.druid.loginPassword}\n    dynamic:\n      druid:\n        initial-size: ${global.datasource.druid.initial-size}\n        min-idle: ${global.datasource.druid.min-idle}\n        maxActive: ${global.datasource.druid.maxActive}\n        maxWait: ${global.datasource.druid.maxWait}\n        connectTimeout: ${global.datasource.druid.connectTimeout}\n        socketTimeout: ${global.datasource.druid.socketTimeout}\n        timeBetweenEvictionRunsMillis: ${global.datasource.druid.timeBetweenEvictionRunsMillis}\n        minEvictableIdleTimeMillis: ${global.datasource.druid.minEvictableIdleTimeMillis}\n        validationQuery: ${global.datasource.druid.validationQuery}\n        testWhileIdle: ${global.datasource.druid.testWhileIdle}\n        testOnBorrow: ${global.datasource.druid.testOnBorrow}\n        testOnReturn: ${global.datasource.druid.testOnReturn}\n        poolPreparedStatements: ${global.datasource.druid.poolPreparedStatements}\n        maxPoolPreparedStatementPerConnectionSize: ${global.datasource.druid.maxPoolPreparedStatementPerConnectionSize}\n        filters: ${global.datasource.druid.filters}\n        connectionProperties: ${global.datasource.druid.connectionProperties}\n      datasource:\n          # 主库数据源\n          master:\n            driver-class-name:  ${global.datasource.master.driver-class-name}\n            hostname: ${global.datasource.master.hostname}\n            post: ${global.datasource.master.port}\n            url: jdbc:mysql://${global.datasource.master.hostname}:${global.datasource.master.port}/ry-cloud?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true\n            username: ${global.datasource.master.username}\n            password: ${global.datasource.master.password}\n          # 从库数据源\n          # slave:\n            # username:\n            # password:\n            # url:\n            # driver-class-name:\n\n# mybatis配置\nmybatis:\n    # 搜索指定包别名\n    typeAliasesPackage: com.ruoyi.system\n    # 配置mapper的扫描，找到所有的mapper.xml映射文件\n    mapperLocations: classpath:mapper/**/*.xml\n\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}/system\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'系统模块接口文档\'\n    # 描述\n    description: \'系统模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip','42ca44517824aa59c3e7f72d9e05fa01','2026-06-23 09:47:26','2026-06-23 01:47:27',NULL,'172.20.0.1','I','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(0,206,'ruoyi-file-dev.yml','DEFAULT_GROUP','','# 本地文件上传    \nfile:\n  domain: http://127.0.0.1:9300\n  path: /home/ruoyi/uploadPath\n  prefix: /statics\n# FastDFS配置\nfdfs:\n  domain: http://127.0.0.1\n  soTimeout: 3000\n  connectTimeout: 2000\n  trackerList: 127.0.0.1:22122\n# Minio配置\nminio:\n  url: http://127.0.0.1:9000\n  accessKey: minioadmin\n  secretKey: minioadmin\n  bucketName: test\n# 防盗链配置\nreferer:\n  # 防盗链开关\n  enabled: false\n  # 允许的域名列表\n  allowed-domains: localhost,127.0.0.1,ruoyi.vip,www.ruoyi.vip','82d32cecd74f36585ca78a8cbe22552b','2026-06-23 09:47:26','2026-06-23 01:47:27',NULL,'172.20.0.1','I','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(0,207,'sentinel-ruoyi-gateway','DEFAULT_GROUP','','[\n    {\n        \"resource\": \"ruoyi-auth\",\n        \"count\": 500,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    },\n	{\n        \"resource\": \"ruoyi-system\",\n        \"count\": 1000,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    },\n    {\n        \"resource\": \"ruoyi-vehicle\",\n        \"count\": 3000,\n        \"grade\": 1,\n        \"limitApp\": \"default\",\n        \"strategy\": 0,\n        \"controlBehavior\": 0\n    }\n]','a8ee1fa35c1dbb63de115d08ae84099a','2026-06-23 09:47:26','2026-06-23 01:47:27',NULL,'172.20.0.1','I','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(0,208,'ruoyi-vehicle-dev.yml','DEFAULT_GROUP','','# spring配置\nspring:\n  servlet:\n    multipart:\n      # 单个文件大小\n      max-file-size: ${global.multipart.maxFileSize}\n      # 总上传大小\n      max-request-size: ${global.multipart.maxRequestSize}\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  datasource:\n    druid:\n      stat-view-servlet:\n        enabled: true\n        loginUsername: ${global.datasource.druid.loginUsername}\n        loginPassword: ${global.datasource.druid.loginPassword}\n    dynamic:\n      druid:\n        initial-size: ${global.datasource.druid.initial-size}\n        min-idle: ${global.datasource.druid.min-idle}\n        maxActive: ${global.datasource.druid.maxActive}\n        maxWait: ${global.datasource.druid.maxWait}\n        connectTimeout: ${global.datasource.druid.connectTimeout}\n        socketTimeout: ${global.datasource.druid.socketTimeout}\n        timeBetweenEvictionRunsMillis: ${global.datasource.druid.timeBetweenEvictionRunsMillis}\n        minEvictableIdleTimeMillis: ${global.datasource.druid.minEvictableIdleTimeMillis}\n        validationQuery: ${global.datasource.druid.validationQuery}\n        testWhileIdle: ${global.datasource.druid.testWhileIdle}\n        testOnBorrow: ${global.datasource.druid.testOnBorrow}\n        testOnReturn: ${global.datasource.druid.testOnReturn}\n        poolPreparedStatements: ${global.datasource.druid.poolPreparedStatements}\n        maxPoolPreparedStatementPerConnectionSize: ${global.datasource.druid.maxPoolPreparedStatementPerConnectionSize}\n        filters: ${global.datasource.druid.filters}\n        connectionProperties: ${global.datasource.druid.connectionProperties}\n      datasource:\n          # 主库数据源\n          master:\n            driver-class-name:  ${global.datasource.master.driver-class-name}\n            url: jdbc:mysql://${global.datasource.master.hostname}:${global.datasource.master.port}/ry-cloud?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true\n            username: ${global.datasource.master.username}\n            password: ${global.datasource.master.password}\n          # 从库数据源\n          # slave:\n            # username: \n            # password: \n            # url: \n            # driver-class-name: \n\n# mybatis配置\nmybatis:\n    # 搜索指定包别名\n    typeAliasesPackage: com.ruoyi.vehicle\n    # 配置mapper的扫描，找到自己的mapper.xml映射文件\n    mapperLocations: classpath:mapper/**/*.xml\n\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'汽车信息模块接口文档\'\n    # 描述\n    description: \'汽车信息模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip\nocr:\n  python:\n    url: http://localhost:5000/ocr/pdf\n  callback:\n    url: http://localhost:8080','e2e520a46bb71d0083a5c0b0a25848ba','2026-06-23 09:47:26','2026-06-23 01:47:27',NULL,'172.20.0.1','I','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(24,209,'application-dev.yml','DEFAULT_GROUP','','global:\n  # 文件限制\n  multipart:\n    # 单个文件大小\n    maxFileSize: 10MB\n    # 总上传大小\n    maxRequestSize: 20MB\n  redis:\n    host: ruoyi-redis\n    port: 6379\n    password:\n  datasource:\n    druid:\n      # Druid监控页面登录用户名\n      loginUsername: ruoyi\n      # Druid监控页面登录密码\n      loginPassword: 123456\n      # 初始化连接数\n      initial-size: 5\n      # 最小空闲连接数\n      min-idle: 5\n      # 最大活跃连接数\n      maxActive: 300\n      # 获取连接的最大等待时间（毫秒），超时抛异常\n      maxWait: 30000\n      # 建立数据库连接的超时时间（毫秒）\n      connectTimeout: 30000\n      # Socket读取超时时间（毫秒）\n      socketTimeout: 60000\n      # 检测空闲连接的间隔时间（毫秒）\n      timeBetweenEvictionRunsMillis: 60000\n      # 连接保持空闲的最小时间（毫秒），超过则被回收\n      minEvictableIdleTimeMillis: 300000\n      # 验证连接有效性的SQL语句\n      validationQuery: SELECT 1 FROM DUAL\n      # 空闲时检测连接是否有效\n      testWhileIdle: true\n      # 获取连接时不检测（性能优化）\n      testOnBorrow: false\n      # 归还连接时不检测（性能优化）\n      testOnReturn: false\n      # 开启PSCache（PreparedStatement缓存）\n      poolPreparedStatements: true\n      # 每个连接的PSCache大小\n      maxPoolPreparedStatementPerConnectionSize: 20\n      # 启用监控统计（stat）和日志（slf4j）过滤器\n      filters: stat,slf4j\n      # 连接属性配置：合并相同SQL统计，慢SQL阈值5秒\n      connectionProperties: druid.stat.mergeSql\\=true;druid.stat.slowSqlMillis\\=5000\n    # 主库数据源\n    master:\n      driver-class-name: com.mysql.cj.jdbc.Driver\n      hostname: ruoyi-mysql\n      port: 3306\n      username: root\n      password: password\n  springdoc:\n    baseUrl: http://192.168.1.239:8080\n    enabled: true\n  # 监控信息\n  security:\n    name: ruoyi\n    password: 123456\n\nspring:\n  autoconfigure:\n    exclude: com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure\n  cloud:\n    gateway:\n      httpclient:\n        response-timeout: 300s  # SSE 超时时间\n      default-filters:\n        - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE\n        \n# feign 配置\nfeign:\n  sentinel:\n    enabled: true\n  okhttp:\n    enabled: true\n  httpclient:\n    enabled: false\n  client:\n    config:\n      default:\n        connectTimeout: 10000\n        readTimeout: 10000\n  compression:\n    request:\n      enabled: true\n      min-request-size: 8192\n    response:\n      enabled: true\n\n# 暴露监控端点\nmanagement:\n  endpoints:\n    web:\n      exposure:\n        include: \'*\'','7e67cdf833cadb69bf3a36c58c031e64','2026-06-23 09:48:23','2026-06-23 01:48:24',NULL,'172.20.0.1','U','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(28,210,'ruoyi-system-dev.yml','DEFAULT_GROUP','','# spring配置\nspring:\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  datasource:\n    druid:\n      stat-view-servlet:\n        enabled: true\n        loginUsername: ${global.datasource.druid.loginUsername}\n        loginPassword: ${global.datasource.druid.loginPassword}\n    dynamic:\n      druid:\n        initial-size: ${global.datasource.druid.initial-size}\n        min-idle: ${global.datasource.druid.min-idle}\n        maxActive: ${global.datasource.druid.maxActive}\n        maxWait: ${global.datasource.druid.maxWait}\n        connectTimeout: ${global.datasource.druid.connectTimeout}\n        socketTimeout: ${global.datasource.druid.socketTimeout}\n        timeBetweenEvictionRunsMillis: ${global.datasource.druid.timeBetweenEvictionRunsMillis}\n        minEvictableIdleTimeMillis: ${global.datasource.druid.minEvictableIdleTimeMillis}\n        validationQuery: ${global.datasource.druid.validationQuery}\n        testWhileIdle: ${global.datasource.druid.testWhileIdle}\n        testOnBorrow: ${global.datasource.druid.testOnBorrow}\n        testOnReturn: ${global.datasource.druid.testOnReturn}\n        poolPreparedStatements: ${global.datasource.druid.poolPreparedStatements}\n        maxPoolPreparedStatementPerConnectionSize: ${global.datasource.druid.maxPoolPreparedStatementPerConnectionSize}\n        filters: ${global.datasource.druid.filters}\n        connectionProperties: ${global.datasource.druid.connectionProperties}\n      datasource:\n          # 主库数据源\n          master:\n            driver-class-name:  ${global.datasource.master.driver-class-name}\n            hostname: ${global.datasource.master.hostname}\n            post: ${global.datasource.master.port}\n            url: jdbc:mysql://${global.datasource.master.hostname}:${global.datasource.master.port}/ry-cloud?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true\n            username: ${global.datasource.master.username}\n            password: ${global.datasource.master.password}\n          # 从库数据源\n          # slave:\n            # username:\n            # password:\n            # url:\n            # driver-class-name:\n\n# mybatis配置\nmybatis:\n    # 搜索指定包别名\n    typeAliasesPackage: com.ruoyi.system\n    # 配置mapper的扫描，找到所有的mapper.xml映射文件\n    mapperLocations: classpath:mapper/**/*.xml\n\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}/system\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'系统模块接口文档\'\n    # 描述\n    description: \'系统模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip','42ca44517824aa59c3e7f72d9e05fa01','2026-06-23 09:49:25','2026-06-23 01:49:26',NULL,'172.20.0.1','U','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL),(31,211,'ruoyi-vehicle-dev.yml','DEFAULT_GROUP','','# spring配置\nspring:\n  servlet:\n    multipart:\n      # 单个文件大小\n      max-file-size: ${global.multipart.maxFileSize}\n      # 总上传大小\n      max-request-size: ${global.multipart.maxRequestSize}\n  redis:\n    host: ${global.redis.host}\n    port: ${global.redis.port}\n    password: ${global.redis.password}\n  datasource:\n    druid:\n      stat-view-servlet:\n        enabled: true\n        loginUsername: ${global.datasource.druid.loginUsername}\n        loginPassword: ${global.datasource.druid.loginPassword}\n    dynamic:\n      druid:\n        initial-size: ${global.datasource.druid.initial-size}\n        min-idle: ${global.datasource.druid.min-idle}\n        maxActive: ${global.datasource.druid.maxActive}\n        maxWait: ${global.datasource.druid.maxWait}\n        connectTimeout: ${global.datasource.druid.connectTimeout}\n        socketTimeout: ${global.datasource.druid.socketTimeout}\n        timeBetweenEvictionRunsMillis: ${global.datasource.druid.timeBetweenEvictionRunsMillis}\n        minEvictableIdleTimeMillis: ${global.datasource.druid.minEvictableIdleTimeMillis}\n        validationQuery: ${global.datasource.druid.validationQuery}\n        testWhileIdle: ${global.datasource.druid.testWhileIdle}\n        testOnBorrow: ${global.datasource.druid.testOnBorrow}\n        testOnReturn: ${global.datasource.druid.testOnReturn}\n        poolPreparedStatements: ${global.datasource.druid.poolPreparedStatements}\n        maxPoolPreparedStatementPerConnectionSize: ${global.datasource.druid.maxPoolPreparedStatementPerConnectionSize}\n        filters: ${global.datasource.druid.filters}\n        connectionProperties: ${global.datasource.druid.connectionProperties}\n      datasource:\n          # 主库数据源\n          master:\n            driver-class-name:  ${global.datasource.master.driver-class-name}\n            url: jdbc:mysql://${global.datasource.master.hostname}:${global.datasource.master.port}/ry-cloud?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true\n            username: ${global.datasource.master.username}\n            password: ${global.datasource.master.password}\n          # 从库数据源\n          # slave:\n            # username: \n            # password: \n            # url: \n            # driver-class-name: \n\n# mybatis配置\nmybatis:\n    # 搜索指定包别名\n    typeAliasesPackage: com.ruoyi.vehicle\n    # 配置mapper的扫描，找到自己的mapper.xml映射文件\n    mapperLocations: classpath:mapper/**/*.xml\n\n# springdoc配置\nspringdoc:\n  gatewayUrl: ${global.springdoc.baseUrl}\n  api-docs:\n    # 是否开启接口文档\n    enabled: ${global.springdoc.enabled}\n  info:\n    # 标题\n    title: \'汽车信息模块接口文档\'\n    # 描述\n    description: \'汽车信息模块接口描述\'\n    # 作者信息\n    contact:\n      name: RuoYi\n      url: https://ruoyi.vip\nocr:\n  python:\n    url: http://localhost:5000/ocr/pdf\n  callback:\n    url: http://localhost:8080','e2e520a46bb71d0083a5c0b0a25848ba','2026-06-23 09:49:53','2026-06-23 01:49:54',NULL,'172.20.0.1','U','172e7c67-00d9-4489-92e9-234aebfc71c0','','formal',NULL,NULL);
/*!40000 ALTER TABLE `his_config_info` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `permissions`
--

DROP TABLE IF EXISTS `permissions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `permissions` (
  `role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'role',
  `resource` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'resource',
  `action` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'action',
  UNIQUE KEY `uk_role_permission` (`role`,`resource`,`action`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `permissions`
--

LOCK TABLES `permissions` WRITE;
/*!40000 ALTER TABLE `permissions` DISABLE KEYS */;
/*!40000 ALTER TABLE `permissions` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_blob_triggers`
--

DROP TABLE IF EXISTS `qrtz_blob_triggers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_blob_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `blob_data` blob COMMENT '存放持久化Trigger对象',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  CONSTRAINT `qrtz_blob_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Blob类型的触发器表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_blob_triggers`
--

LOCK TABLES `qrtz_blob_triggers` WRITE;
/*!40000 ALTER TABLE `qrtz_blob_triggers` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_blob_triggers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_calendars`
--

DROP TABLE IF EXISTS `qrtz_calendars`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_calendars` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `calendar_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '日历名称',
  `calendar` blob NOT NULL COMMENT '存放持久化calendar对象',
  PRIMARY KEY (`sched_name`,`calendar_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='日历信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_calendars`
--

LOCK TABLES `qrtz_calendars` WRITE;
/*!40000 ALTER TABLE `qrtz_calendars` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_calendars` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_cron_triggers`
--

DROP TABLE IF EXISTS `qrtz_cron_triggers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_cron_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `cron_expression` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'cron表达式',
  `time_zone_id` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '时区',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  CONSTRAINT `qrtz_cron_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Cron类型的触发器表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_cron_triggers`
--

LOCK TABLES `qrtz_cron_triggers` WRITE;
/*!40000 ALTER TABLE `qrtz_cron_triggers` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_cron_triggers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_fired_triggers`
--

DROP TABLE IF EXISTS `qrtz_fired_triggers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_fired_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `entry_id` varchar(95) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度器实例id',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `instance_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度器实例名',
  `fired_time` bigint NOT NULL COMMENT '触发的时间',
  `sched_time` bigint NOT NULL COMMENT '定时器制定的时间',
  `priority` int NOT NULL COMMENT '优先级',
  `state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '状态',
  `job_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '任务名称',
  `job_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '任务组名',
  `is_nonconcurrent` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否并发',
  `requests_recovery` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否接受恢复执行',
  PRIMARY KEY (`sched_name`,`entry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='已触发的触发器表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_fired_triggers`
--

LOCK TABLES `qrtz_fired_triggers` WRITE;
/*!40000 ALTER TABLE `qrtz_fired_triggers` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_fired_triggers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_job_details`
--

DROP TABLE IF EXISTS `qrtz_job_details`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_job_details` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `job_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务名称',
  `job_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务组名',
  `description` varchar(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '相关介绍',
  `job_class_name` varchar(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '执行任务类名称',
  `is_durable` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '是否持久化',
  `is_nonconcurrent` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '是否并发',
  `is_update_data` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '是否更新数据',
  `requests_recovery` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '是否接受恢复执行',
  `job_data` blob COMMENT '存放持久化job对象',
  PRIMARY KEY (`sched_name`,`job_name`,`job_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='任务详细信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_job_details`
--

LOCK TABLES `qrtz_job_details` WRITE;
/*!40000 ALTER TABLE `qrtz_job_details` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_job_details` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_locks`
--

DROP TABLE IF EXISTS `qrtz_locks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_locks` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `lock_name` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '悲观锁名称',
  PRIMARY KEY (`sched_name`,`lock_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='存储的悲观锁信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_locks`
--

LOCK TABLES `qrtz_locks` WRITE;
/*!40000 ALTER TABLE `qrtz_locks` DISABLE KEYS */;
INSERT INTO `qrtz_locks` VALUES ('RuoYiScheduler','STATE_ACCESS'),('RuoYiScheduler','TRIGGER_ACCESS');
/*!40000 ALTER TABLE `qrtz_locks` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_paused_trigger_grps`
--

DROP TABLE IF EXISTS `qrtz_paused_trigger_grps`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_paused_trigger_grps` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  PRIMARY KEY (`sched_name`,`trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='暂停的触发器表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_paused_trigger_grps`
--

LOCK TABLES `qrtz_paused_trigger_grps` WRITE;
/*!40000 ALTER TABLE `qrtz_paused_trigger_grps` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_paused_trigger_grps` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_scheduler_state`
--

DROP TABLE IF EXISTS `qrtz_scheduler_state`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_scheduler_state` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `instance_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '实例名称',
  `last_checkin_time` bigint NOT NULL COMMENT '上次检查时间',
  `checkin_interval` bigint NOT NULL COMMENT '检查间隔时间',
  PRIMARY KEY (`sched_name`,`instance_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='调度器状态表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_scheduler_state`
--

LOCK TABLES `qrtz_scheduler_state` WRITE;
/*!40000 ALTER TABLE `qrtz_scheduler_state` DISABLE KEYS */;
INSERT INTO `qrtz_scheduler_state` VALUES ('RuoYiScheduler','N_94264261778655158650',1778655667067,10000);
/*!40000 ALTER TABLE `qrtz_scheduler_state` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_simple_triggers`
--

DROP TABLE IF EXISTS `qrtz_simple_triggers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_simple_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `repeat_count` bigint NOT NULL COMMENT '重复的次数统计',
  `repeat_interval` bigint NOT NULL COMMENT '重复的间隔时间',
  `times_triggered` bigint NOT NULL COMMENT '已经触发的次数',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  CONSTRAINT `qrtz_simple_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='简单触发器的信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_simple_triggers`
--

LOCK TABLES `qrtz_simple_triggers` WRITE;
/*!40000 ALTER TABLE `qrtz_simple_triggers` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_simple_triggers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_simprop_triggers`
--

DROP TABLE IF EXISTS `qrtz_simprop_triggers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_simprop_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `str_prop_1` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'String类型的trigger的第一个参数',
  `str_prop_2` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'String类型的trigger的第二个参数',
  `str_prop_3` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'String类型的trigger的第三个参数',
  `int_prop_1` int DEFAULT NULL COMMENT 'int类型的trigger的第一个参数',
  `int_prop_2` int DEFAULT NULL COMMENT 'int类型的trigger的第二个参数',
  `long_prop_1` bigint DEFAULT NULL COMMENT 'long类型的trigger的第一个参数',
  `long_prop_2` bigint DEFAULT NULL COMMENT 'long类型的trigger的第二个参数',
  `dec_prop_1` decimal(13,4) DEFAULT NULL COMMENT 'decimal类型的trigger的第一个参数',
  `dec_prop_2` decimal(13,4) DEFAULT NULL COMMENT 'decimal类型的trigger的第二个参数',
  `bool_prop_1` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Boolean类型的trigger的第一个参数',
  `bool_prop_2` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Boolean类型的trigger的第二个参数',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  CONSTRAINT `qrtz_simprop_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='同步机制的行锁表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_simprop_triggers`
--

LOCK TABLES `qrtz_simprop_triggers` WRITE;
/*!40000 ALTER TABLE `qrtz_simprop_triggers` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_simprop_triggers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `qrtz_triggers`
--

DROP TABLE IF EXISTS `qrtz_triggers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '触发器的名字',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '触发器所属组的名字',
  `job_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_job_details表job_name的外键',
  `job_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_job_details表job_group的外键',
  `description` varchar(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '相关介绍',
  `next_fire_time` bigint DEFAULT NULL COMMENT '上一次触发时间（毫秒）',
  `prev_fire_time` bigint DEFAULT NULL COMMENT '下一次触发时间（默认为-1表示不触发）',
  `priority` int DEFAULT NULL COMMENT '优先级',
  `trigger_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '触发器状态',
  `trigger_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '触发器的类型',
  `start_time` bigint NOT NULL COMMENT '开始时间',
  `end_time` bigint DEFAULT NULL COMMENT '结束时间',
  `calendar_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '日程表名称',
  `misfire_instr` smallint DEFAULT NULL COMMENT '补偿执行的策略',
  `job_data` blob COMMENT '存放持久化job对象',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`),
  KEY `sched_name` (`sched_name`,`job_name`,`job_group`),
  CONSTRAINT `qrtz_triggers_ibfk_1` FOREIGN KEY (`sched_name`, `job_name`, `job_group`) REFERENCES `qrtz_job_details` (`sched_name`, `job_name`, `job_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='触发器详细信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `qrtz_triggers`
--

LOCK TABLES `qrtz_triggers` WRITE;
/*!40000 ALTER TABLE `qrtz_triggers` DISABLE KEYS */;
/*!40000 ALTER TABLE `qrtz_triggers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `roles`
--

DROP TABLE IF EXISTS `roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roles` (
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'username',
  `role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'role',
  UNIQUE KEY `idx_user_role` (`username`,`role`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `roles`
--

LOCK TABLES `roles` WRITE;
/*!40000 ALTER TABLE `roles` DISABLE KEYS */;
INSERT INTO `roles` VALUES ('nacos','ROLE_ADMIN');
/*!40000 ALTER TABLE `roles` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `tenant_capacity`
--

DROP TABLE IF EXISTS `tenant_capacity`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tenant_capacity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL DEFAULT '' COMMENT 'Tenant ID',
  `quota` int unsigned NOT NULL DEFAULT '0' COMMENT '配额，0表示使用默认值',
  `usage` int unsigned NOT NULL DEFAULT '0' COMMENT '使用量',
  `max_size` int unsigned NOT NULL DEFAULT '0' COMMENT '单个配置大小上限，单位为字节，0表示使用默认值',
  `max_aggr_count` int unsigned NOT NULL DEFAULT '0' COMMENT '聚合子配置最大个数',
  `max_aggr_size` int unsigned NOT NULL DEFAULT '0' COMMENT '单个聚合数据的子配置大小上限，单位为字节，0表示使用默认值',
  `max_history_count` int unsigned NOT NULL DEFAULT '0' COMMENT '最大变更历史数量',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='租户容量信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tenant_capacity`
--

LOCK TABLES `tenant_capacity` WRITE;
/*!40000 ALTER TABLE `tenant_capacity` DISABLE KEYS */;
/*!40000 ALTER TABLE `tenant_capacity` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `tenant_info`
--

DROP TABLE IF EXISTS `tenant_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tenant_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `kp` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT 'kp',
  `tenant_id` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT '' COMMENT 'tenant_id',
  `tenant_name` varchar(128) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT '' COMMENT 'tenant_name',
  `tenant_desc` varchar(256) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'tenant_desc',
  `create_source` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'create_source',
  `gmt_create` bigint NOT NULL COMMENT '创建时间',
  `gmt_modified` bigint NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_info_kptenantid` (`kp`,`tenant_id`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='tenant_info';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tenant_info`
--

LOCK TABLES `tenant_info` WRITE;
/*!40000 ALTER TABLE `tenant_info` DISABLE KEYS */;
INSERT INTO `tenant_info` VALUES (1,'1','172e7c67-00d9-4489-92e9-234aebfc71c0','prod','正式环境','nacos',1782179040810,1782179040810);
/*!40000 ALTER TABLE `tenant_info` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'username',
  `password` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'password',
  `enabled` tinyint(1) NOT NULL COMMENT 'enabled',
  PRIMARY KEY (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `users`
--

LOCK TABLES `users` WRITE;
/*!40000 ALTER TABLE `users` DISABLE KEYS */;
INSERT INTO `users` VALUES ('nacos','$2a$10$EuWPZHzz32dJN7jexM34MOeYirDdFAZm2kuWj7VEOJhhZkDrxfvUu',1);
/*!40000 ALTER TABLE `users` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping routines for database 'ry-config'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-06-23 10:11:13
