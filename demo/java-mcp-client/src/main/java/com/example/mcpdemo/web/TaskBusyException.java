package com.example.mcpdemo.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * W2：入口并发已满（{@code /api/task} 同时执行数达到上限）。
 *
 * <p>返回 503 + {@code Retry-After: 1}，让调用方/压测脚本能区分"系统繁忙"和"任务本身失败"，
 * 而不是傻等一个 300 秒的总超时。
 */
public class TaskBusyException extends ResponseStatusException {

    public TaskBusyException(int inFlight, int capacity) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "系统繁忙：当前并发任务 " + inFlight + "/" + capacity + "，请稍后重试");
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "1");
        return headers;
    }
}
