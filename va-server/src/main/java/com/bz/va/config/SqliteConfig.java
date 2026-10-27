package com.bz.va.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** SQLite 数据源（任务库）：WAL + busy_timeout，零运维。 */
@Configuration
public class SqliteConfig {

    @Bean
    public DataSource dataSource(VaProperties properties) throws IOException {
        Path dir = properties.getDataDir().toAbsolutePath().normalize();
        Files.createDirectories(dir);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("va.sqlite3"));
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);
        dataSource.setConfig(config);
        return dataSource;
    }
}
