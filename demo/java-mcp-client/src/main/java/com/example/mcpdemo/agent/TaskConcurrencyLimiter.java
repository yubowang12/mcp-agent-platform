package com.example.mcpdemo.agent;

import com.example.mcpdemo.config.PlannerProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * W2：入口并发闸门。
 *
 * <p>作用是把"同时执行的任务数"提前卡住：HTTP 线程在进入编排前先 {@link #tryEnter()}，
 * 拿不到许可立刻返回 503，而不是让请求堆在 Tomcat 线程或 LLM 队列里慢慢超时。
 *
 * <p>选 {@link Semaphore} 而不是计数器 + 锁，是因为它自带"非阻塞尝试获取"，语义正好；
 * 公平模式保证先到的请求先拿到许可，避免长任务把后来者饿死。
 *
 * <p>许可必须成对释放：调用方用 {@code try { ... } finally { exit(); }} 包住编排逻辑。
 */
@Component
public class TaskConcurrencyLimiter {

    private final Semaphore permits;
    private final int capacity;

    public TaskConcurrencyLimiter(PlannerProperties props) {
        this.capacity = Math.max(1, props.maxConcurrentTasks());
        this.permits = new Semaphore(capacity, true);
    }

    /** 尝试占用一个执行位；满则立即返回 false（不等待）。 */
    public boolean tryEnter() {
        return permits.tryAcquire();
    }

    /** 释放一个执行位（必须与一次成功的 {@link #tryEnter()} 配对）。 */
    public void exit() {
        permits.release();
    }

    /** 当前执行中的任务数（用于 503 文案与测试）。 */
    public int inFlight() {
        return capacity - permits.availablePermits();
    }

    /** 并发上限（配置 {@code mcp.planner.max-concurrent-tasks}）。 */
    public int capacity() {
        return capacity;
    }
}
