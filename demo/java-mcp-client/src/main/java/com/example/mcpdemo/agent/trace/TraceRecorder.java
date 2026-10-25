package com.example.mcpdemo.agent.trace;

import com.example.mcpdemo.agent.model.TaskTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

/**
 * M3：trace 落盘器。
 *
 * <p>每条 trace 追加为 JSONL 中的一行，文件按日期切分：
 * {@code <traceDir>/yyyy-MM-dd.jsonl}。
 *
 * <p>设计要点：
 * <ul>
 *   <li>目录在 {@link PostConstruct} 阶段创建，启动期暴露路径问题；</li>
 *   <li>{@code append} 加 {@code synchronized}，避免并发请求写同行交错；</li>
 *   <li>落盘失败只记日志、不抛异常——trace 是辅助数据，不能拖垮任务。</li>
 * </ul>
 */
@Component
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final ObjectMapper objectMapper;
    private final Path traceDir;

    public TraceRecorder(ObjectMapper objectMapper,
                         @Value("${mcp.trace.dir:demo/logs/trace}") String traceDir) {
        this.objectMapper = objectMapper;
        this.traceDir = Path.of(traceDir);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(traceDir);
        log.info("trace 目录就绪: {}", traceDir.toAbsolutePath());
    }

    /**
     * 追加一条 trace（JSONL：一行一个 JSON 对象）。
     */
    public synchronized void append(TaskTrace trace) {
        try {
            String line = objectMapper.writeValueAsString(trace) + System.lineSeparator();
            Path file = traceDir.resolve(LocalDate.now() + ".jsonl");
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("trace 已落盘: taskId={}, file={}", trace.taskId(), file);
        } catch (IOException e) {
            log.warn("trace 落盘失败: taskId={}", trace.taskId(), e);
        }
    }
}
