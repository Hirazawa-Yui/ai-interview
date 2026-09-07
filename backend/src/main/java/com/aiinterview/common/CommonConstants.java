package com.aiinterview.common;

/**
 * 通用常量
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    /**
     * HTTP状态码（业务层使用，非HTTP响应码）
     */
    public static final class StatusCode {
        /** 成功 */
        public static final Integer SUCCESS = 200;
        /** 服务器内部错误 */
        public static final Integer SERVER_ERROR = 500;

        private StatusCode() {
        }
    }
}
