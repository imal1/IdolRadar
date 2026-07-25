package com.idolradar.seed;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 管理员维护的 JSONL 种子文件目录。 */
@ConfigurationProperties(prefix = "idolradar.seed")
public class SeedProperties {

    private Path directory = Path.of("database");
    private URI rsshubBaseUrl = URI.create("http://127.0.0.1:1200");

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    public URI getRsshubBaseUrl() {
        return rsshubBaseUrl;
    }

    public void setRsshubBaseUrl(URI rsshubBaseUrl) {
        this.rsshubBaseUrl = rsshubBaseUrl;
    }
}
