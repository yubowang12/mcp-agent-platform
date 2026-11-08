package com.example.mcpdemo.memory.longterm;

import com.example.mcpdemo.memory.config.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * embedding 调用封装（规格第 9 节：复用 Spring AI 装配好的 {@link EmbeddingModel}）。
 *
 * <p>模型名与维度来自配置 {@code mcp.memory.embedding.*}（2026-11-07 实测：
 * DashScope {@code /compatible-mode/v1/embeddings} + {@code text-embedding-v3} = 1024 维），
 * 代码里不写死；换模型/维度只改配置。
 *
 * <p>与 RAG 共用同一个模型/维度（决策 4）——第 3 步之后 RAG 直接复用本类即可。
 *
 * <p>失败返回 {@code null}：调用方按"本次不召回 / 本条不写向量"降级，不抛异常。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mcp.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingService {

    /** Spring AI 装配好的 embedding 模型（DashScope OpenAI 兼容接口）。 */
    private final EmbeddingModel embeddingModel;
    /** 记忆层配置（取 embedding 的模型名与维度，不写死在代码里）。 */
    private final MemoryProperties props;

    public EmbeddingService(EmbeddingModel embeddingModel, MemoryProperties props) {
        this.embeddingModel = embeddingModel;
        this.props = props;
        log.info("embedding 就绪: model={}, dimensions={}, modelBean={}",
                props.embedding().model(), props.embedding().dimensions(),
                embeddingModel == null ? "null" : embeddingModel.getClass().getSimpleName());
    }

    public boolean enabled() {
        return props.embedding().enabled() && embeddingModel != null;
    }

    public int dimensions() {
        return props.embedding().dimensions();
    }

    /**
     * 把文本转向量；不可用或调用失败返回 null。
     */
    public float[] embed(String text) {
        if (!enabled() || text == null || text.isBlank()) {
            return null;
        }
        try {
            EmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .model(props.embedding().model())
                    .dimensions(props.embedding().dimensions())
                    .build();
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of(text), options));
            float[] vector = response == null || response.getResult() == null
                    ? null
                    : response.getResult().getOutput();
            if (vector == null || vector.length == 0) {
                log.warn("embedding 返回空向量: model={}", props.embedding().model());
                return null;
            }
            if (vector.length != props.embedding().dimensions()) {
                log.warn("embedding 维度与配置不一致（按实际长度存储）: 实际={}, 配置={}",
                        vector.length, props.embedding().dimensions());
            }
            return vector;
        } catch (Exception e) {
            log.warn("embedding 调用失败（本次按无向量降级）: model={}", props.embedding().model(), e);
            return null;
        }
    }
}
