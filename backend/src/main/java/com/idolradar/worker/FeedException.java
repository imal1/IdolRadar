package com.idolradar.worker;

/** RSS 抓取或解析失败；携带稳定、机器可读的错误码。 */
public class FeedException extends RuntimeException {
    private final String code;

    public FeedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public FeedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
