package com.ziwei.zw.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应体。
 *
 * @param code 0 表示成功，其余为业务错误码
 * @param msg  提示信息
 * @param data 数据
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record R<T>(int code, String msg, T data) {

    public static final int OK = 0;

    public static <T> R<T> ok(T data) {
        return new R<>(OK, "ok", data);
    }

    public static R<Void> ok() {
        return new R<>(OK, "ok", null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(500, msg, null);
    }
}
