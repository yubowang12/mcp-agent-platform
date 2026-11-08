package com.example.mcpdemo.memory;

/**
 * 向量编解码与相似度（本版只做余弦）。
 *
 * <p>存储格式按规格第 4 节：float32 **小端**，BLOB 长度 = dim * 4。
 */
public final class MemoryVectors {

    private MemoryVectors() {
    }

    /** float[] → BLOB（float32 小端）。 */
    public static byte[] toBlob(float[] vector) {
        if (vector == null) {
            return null;
        }
        byte[] bytes = new byte[vector.length * 4];
        for (int i = 0; i < vector.length; i++) {
            int bits = Float.floatToIntBits(vector[i]);
            bytes[i * 4] = (byte) (bits & 0xFF);
            bytes[i * 4 + 1] = (byte) ((bits >> 8) & 0xFF);
            bytes[i * 4 + 2] = (byte) ((bits >> 16) & 0xFF);
            bytes[i * 4 + 3] = (byte) ((bits >> 24) & 0xFF);
        }
        return bytes;
    }

    /** BLOB → float[]（与 {@link #toBlob} 对称）。 */
    public static float[] fromBlob(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        if (blob.length % 4 != 0) {
            throw new IllegalArgumentException("向量长度不是 4 的倍数: " + blob.length);
        }
        float[] vector = new float[blob.length / 4];
        for (int i = 0; i < vector.length; i++) {
            int bits = (blob[i * 4] & 0xFF)
                    | ((blob[i * 4 + 1] & 0xFF) << 8)
                    | ((blob[i * 4 + 2] & 0xFF) << 16)
                    | ((blob[i * 4 + 3] & 0xFF) << 24);
            vector[i] = Float.intBitsToFloat(bits);
        }
        return vector;
    }

    /**
     * 余弦相似度；任一向量为空 / 维度不一致 / 零向量时返回 0（当作不相似）。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
