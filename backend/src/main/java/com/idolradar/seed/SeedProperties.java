package com.idolradar.seed;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 管理员维护的 JSONL 种子文件目录。 */
@ConfigurationProperties(prefix = "idolradar.seed")
public class SeedProperties {

    private Path directory = Path.of("database");

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }
}
