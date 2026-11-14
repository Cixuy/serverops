package com.qingruan.common.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.HealthCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

@Slf4j
@Component
@RequiredArgsConstructor
public class UnifiedHealthChecker implements ApplicationRunner {

    // 核心数据源（必须存在，否则应用无法启动）
    private final DataSource dataSource;

    // 可选数据源（使用 ObjectProvider 防止 Bean 缺失时导致启动报错）
    private final ObjectProvider<InfluxDBClient> influxDBClientProvider;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider;

    @Override
    public void run(ApplicationArguments args) {
        log.info("================ 🩺 开始数据源健康检查 ================");
        long startTime = System.currentTimeMillis();

        // 依次执行各项检查，互不影响
        checkMySQL();
        checkInfluxDB();
        checkRedis();

        long duration = System.currentTimeMillis() - startTime;
        log.info("================ ✅ 健康检查完成 (耗时: {}ms) ================", duration);
    }

    /**
     * 检查 MySQL 连接
     */
    private void checkMySQL() {
        try (Connection conn = dataSource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName();
            String version = conn.getMetaData().getDatabaseProductVersion();
            log.info("✅ [MySQL] 连接正常 | {} {}", productName, version);
        } catch (SQLException e) {
            log.error("❌ [MySQL] 连接失败: {}", e.getMessage());
            // 如果 MySQL 是核心依赖，可以在此处抛出异常阻断启动：
            // throw new IllegalStateException("MySQL 不可用，应用终止", e);
        }
    }

    /**
     * 检查 InfluxDB 连接
     */
    private void checkInfluxDB() {
        InfluxDBClient client = influxDBClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("⚠️ [InfluxDB] 未检测到 InfluxDBClient Bean，跳过检查");
            return;
        }

        try {
            HealthCheck health = client.health();
            if (health.getStatus() == HealthCheck.StatusEnum.PASS) {
                log.info("✅ [InfluxDB] 连接正常 | version: {}", health.getVersion());
            } else {
                log.error("❌ [InfluxDB] 健康检查未通过: {}", health.getMessage());
            }
        } catch (Exception e) {
            log.error("❌ [InfluxDB] 连接失败: {}", e.getMessage());
        }
    }

    /**
     * 检查 Redis 连接
     */
    private void checkRedis() {
        RedisConnectionFactory factory = redisConnectionFactoryProvider.getIfAvailable();
        if (factory == null) {
            log.warn("⚠️ [Redis] 未检测到 RedisConnectionFactory Bean，跳过检查");
            return;
        }

        try (var connection = factory.getConnection()) {
            // PING 是最轻量的 Redis 健康检查命令
            String pong = connection.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                // 顺便获取 Redis 版本信息
                Properties info = connection.info("server");
                String version = info != null ? info.getProperty("redis_version") : "unknown";
                log.info("✅ [Redis] 连接正常 | version: {}", version);
            } else {
                log.error("❌ [Redis] PING 响应异常: {}", pong);
            }
        } catch (Exception e) {
            log.error("❌ [Redis] 连接失败: {}", e.getMessage());
        }
    }
}