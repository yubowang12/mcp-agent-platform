package com.example.mcpdemo.memory;

import com.example.mcpdemo.memory.config.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 向量召回（规格第 5 节）：query 转向量 → 与库内向量算余弦 → 时间衰减加权 → 过滤过期 → top-k。
 *
 * <p>口径（v1 刻意简单，v2 再加关键词混合 + rerank）：
 * <ol>
 *   <li><b>过期</b>：{@code expires_at < now} 直接不参与（A6）；</li>
 *   <li><b>门槛</b>：原始余弦 &lt; {@code min-score} 丢弃——宁可不注入，也不塞不相干的历史；</li>
 *   <li><b>排序</b>：分数 = 余弦 × 时间衰减，衰减按半衰期 {@code score = 0.5^(age/halfLife)}；</li>
 *   <li><b>截断</b>：只取 {@code top-k}，控制上下文膨胀（风险表最后一条）。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mcp.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryRecallService {

    /** 一天的毫秒数（把时间差换算成"天"算衰减）。 */
    private static final double MILLIS_PER_DAY = 86_400_000.0;

    /** 长期记忆存储（取未过期的候选）。 */
    private final MemoryStore store;
    /** embedding 服务（把 query 转向量）。 */
    private final EmbeddingService embeddingService;
    /** 记忆层配置（top-k / 余弦下限 / 半衰期）。 */
    private final MemoryProperties props;

    public MemoryRecallService(MemoryStore store, EmbeddingService embeddingService, MemoryProperties props) {
        this.store = store;
        this.embeddingService = embeddingService;
        this.props = props;
    }

    /**
     * 召回与 query 最相关的记忆。
     *
     * @return 按最终分降序，最多 {@code mcp.memory.recall.top-k} 条；任何环节不可用都返回空列表
     */
    public List<RecalledMemory> recall(String userId, String query) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        float[] queryVector = embeddingService.embed(query);
        if (queryVector == null) {
            log.debug("query 向量不可用，本次不召回: userId={}", userId);
            return List.of();
        }
        Instant now = Instant.now();
        List<MemoryRecord> candidates = store.findActive(userId, now);
        List<RecalledMemory> scored = new ArrayList<>();
        for (MemoryRecord record : candidates) {
            float[] vector = record.embedding();
            if (vector == null || vector.length != queryVector.length) {
                continue;                       // 没向量或维度对不上（换了 embedding 模型）→ 跳过
            }
            double cosine = MemoryVectors.cosine(queryVector, vector);

            // 低于 余弦相似度下限
            if (cosine < props.recall().minScore()) {
                continue;
            }
            // 时间衰减
            double decay = decay(record.createdAt(), now, props.recall().halfLifeDays());
            scored.add(new RecalledMemory(record, cosine * decay, cosine, decay));
        }
        scored.sort(Comparator.comparingDouble(RecalledMemory::score).reversed());

        // 只召回 top-k (防止上下文过长)
        if (scored.size() > props.recall().topK()) {
            scored = new ArrayList<>(scored.subList(0, props.recall().topK()));
        }
        if (!scored.isEmpty()) {
            log.info("召回 {} 条记忆: userId={}, topScore={}", scored.size(), userId,
                    String.format("%.3f", scored.get(0).score()));
        }
        return scored;
    }

    /**
     * 时间衰减：{@code 0.5 ^ (ageDays / halfLifeDays)}；半衰期配置为 0 或负数时不做衰减。
     */
    static double decay(Instant createdAt, Instant now, double halfLifeDays) {
        if (createdAt == null || halfLifeDays <= 0) {
            return 1.0;
        }
        double ageDays = Duration.between(createdAt, now).toMillis() / MILLIS_PER_DAY;
        if (ageDays <= 0) {
            return 1.0;
        }
        return Math.pow(0.5, ageDays / halfLifeDays);
    }
}
