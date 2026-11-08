package com.example.mcpdemo.memory.longterm;
import com.example.mcpdemo.memory.model.MemoryKind;
import com.example.mcpdemo.memory.model.MemoryRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 长期记忆存储（规格第 5 节）：{@code add / search / forget / purgeExpired}。
 *
 * <p>{@code search} 在第 3 步（embedding 接入）补上；本步先落地写入与查看/删除。
 */
public interface MemoryStore {

    /**
     * 写入一条记忆。
     *
     * @param userId     归属用户
     * @param kind       semantic / episodic
     * @param text       正文
     * @param embedding  float32 向量（第 2 步传 null，第 3 步接 embedding 后填）
     * @param source     来源（conversationId / messageId）
     * @param confidence 置信度
     * @param expiresAt  过期时间，null 表示不过期
     * @return 新写入的记录；同用户下正文完全相同的重复记忆不重复写，返回 empty
     */
    Optional<MemoryRecord> add(String userId, MemoryKind kind, String text, float[] embedding,
                               String source, double confidence, Instant expiresAt);

    /** 某用户的全部记忆（含已过期，供"可查看/可删除"用，A8）。 */
    List<MemoryRecord> list(String userId);

    /** 某用户**未过期**的记忆（召回候选，A6：过期的不参与）。 */
    List<MemoryRecord> findActive(String userId, Instant now);

    /** 删除一条记忆（A8 可删除）。 */
    boolean forget(String userId, String id);

    /** 清理过期记忆，返回删除条数。 */
    int purgeExpired(Instant now);
}
