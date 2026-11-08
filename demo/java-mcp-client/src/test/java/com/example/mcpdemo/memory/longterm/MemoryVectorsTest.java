package com.example.mcpdemo.memory.longterm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量编解码（float32 小端）与余弦相似度。
 */
class MemoryVectorsTest {

    @Test
    void blobRoundTripPreservesValues() {
        float[] vector = new float[] {0.0f, 1.5f, -2.25f, 1e-6f};

        float[] decoded = MemoryVectors.fromBlob(MemoryVectors.toBlob(vector));

        assertEquals(vector.length, decoded.length);
        for (int i = 0; i < vector.length; i++) {
            assertEquals(vector[i], decoded[i], 1e-9);
        }
    }

    @Test
    void blobIsLittleEndianFloat32() {
        // 1.0f 的 IEEE754 是 0x3F800000；小端字节序 = 00 00 80 3F
        byte[] blob = MemoryVectors.toBlob(new float[] {1.0f});

        assertEquals(4, blob.length, "长度必须是 dim * 4");
        assertEquals(0x00, blob[0] & 0xFF);
        assertEquals(0x00, blob[1] & 0xFF);
        assertEquals(0x80, blob[2] & 0xFF);
        assertEquals(0x3F, blob[3] & 0xFF);
    }

    @Test
    void cosineOfIdenticalVectorsIsOne() {
        float[] v = new float[] {0.1f, 0.2f, 0.3f};
        assertEquals(1.0, MemoryVectors.cosine(v, v), 1e-6);
    }

    @Test
    void cosineOfOppositeVectorsIsMinusOne() {
        assertEquals(-1.0, MemoryVectors.cosine(new float[] {1f, 0f}, new float[] {-1f, 0f}), 1e-6);
    }

    @Test
    void cosineOfOrthogonalVectorsIsZero() {
        assertEquals(0.0, MemoryVectors.cosine(new float[] {1f, 0f}, new float[] {0f, 1f}), 1e-6);
    }

    @Test
    void cosineHandlesMissingOrMismatchedVectors() {
        assertEquals(0.0, MemoryVectors.cosine(null, new float[] {1f}));
        assertEquals(0.0, MemoryVectors.cosine(new float[] {1f}, new float[] {1f, 2f}));
        assertEquals(0.0, MemoryVectors.cosine(new float[] {0f}, new float[] {0f}), "零向量按不相似处理");
    }

    @Test
    void nullBlobDecodesToNull() {
        assertNull(MemoryVectors.fromBlob(null));
        assertNull(MemoryVectors.fromBlob(new byte[0]));
        assertTrue(MemoryVectors.toBlob(null) == null);
    }
}
