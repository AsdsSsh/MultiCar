package inspection.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图压缩存储工具
 * 
 * 提供两种压缩策略：
 * 1. RLE（Run-Length Encoding）：对连续的0/1序列进行行程编码
 *    格式：每个元素 = count << 1 | bit，即用整数表示连续相同值的个数
 *    例如：5个连续的0 → 编码为 10（5*2+0），3个连续的1 → 编码为 7（3*2+1）
 * 
 * 2. 分块存储（Chunking）：将大地图按 CHUNK_SIZE 分成小块独立存储
 *    每个块单独使用 RLE 压缩，支持按需加载和增量更新
 */
public final class MapCompression {

    private MapCompression() {}

    /** 分块大小：16×16 = 256 个格子 */
    public static final int CHUNK_SIZE = 16;

    /** 压缩阈值：地图尺寸超过此值时才启用压缩传输 */
    public static final int COMPRESSION_THRESHOLD = 50;

    // ==================== RLE 压缩/解压 ====================

    /**
     * 将二维 int[][] 地图按行进行 RLE 压缩
     * 返回二维数组：compressed[rowIndex] = [编码序列...]
     * 
     * @param map 二维数组（0/1）
     * @return RLE 编码后的二维数组
     */
    public static int[][] compressRLE(int[][] map) {
        if (map == null || map.length == 0) return new int[0][];
        int[][] result = new int[map.length][];
        for (int y = 0; y < map.length; y++) {
            result[y] = compressRowRLE(map[y]);
        }
        return result;
    }

    /**
     * 对单行进行 RLE 压缩
     */
    public static int[] compressRowRLE(int[] row) {
        if (row == null || row.length == 0) return new int[0];
        List<Integer> encoded = new ArrayList<>();
        int count = 1;
        int current = row[0];
        for (int i = 1; i < row.length; i++) {
            if (row[i] == current) {
                count++;
            } else {
                encoded.add((count << 1) | current);
                current = row[i];
                count = 1;
            }
        }
        encoded.add((count << 1) | current);
        return encoded.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 解压 RLE 编码的单行
     * @param encoded RLE 编码数组
     * @param width 目标行宽度
     * @return 解压后的 int[] 数组
     */
    public static int[] decompressRowRLE(int[] encoded, int width) {
        int[] result = new int[width];
        int pos = 0;
        for (int code : encoded) {
            int bit = code & 1;
            int count = code >> 1;
            for (int i = 0; i < count && pos < width; i++) {
                result[pos++] = bit;
            }
        }
        return result;
    }

    /**
     * 解压完整的 RLE 压缩地图
     * @param compressed RLE 编码的二维数组
     * @param width 地图宽度
     * @param height 地图高度
     * @return 解压后的 int[][] 地图
     */
    public static int[][] decompressRLE(int[][] compressed, int width, int height) {
        int[][] result = new int[height][width];
        for (int y = 0; y < Math.min(height, compressed.length); y++) {
            if (compressed[y] != null) {
                result[y] = decompressRowRLE(compressed[y], width);
            }
        }
        return result;
    }

    /**
     * 计算 RLE 压缩率
     * @return 压缩率百分比（越小压缩效果越好）
     */
    public static double compressionRatio(int originalSize, int compressedSize) {
        if (originalSize == 0) return 100.0;
        return (double) compressedSize / originalSize * 100.0;
    }

    // ==================== 分块地图存储 ====================

    /**
     * 将地图分割成多个块
     * @param map 完整地图 int[][]
     * @param mapWidth 地图宽度
     * @param mapHeight 地图高度
     * @return 块列表，每个块包含 {chunkX, chunkY, 压缩数据}
     */
    public static List<MapChunk> splitIntoChunks(int[][] map, int mapWidth, int mapHeight) {
        List<MapChunk> chunks = new ArrayList<>();
        int chunksX = (int) Math.ceil((double) mapWidth / CHUNK_SIZE);
        int chunksY = (int) Math.ceil((double) mapHeight / CHUNK_SIZE);

        for (int cy = 0; cy < chunksY; cy++) {
            for (int cx = 0; cx < chunksX; cx++) {
                int startX = cx * CHUNK_SIZE;
                int startY = cy * CHUNK_SIZE;
                int endX = Math.min(startX + CHUNK_SIZE, mapWidth);
                int endY = Math.min(startY + CHUNK_SIZE, mapHeight);
                int chunkW = endX - startX;
                int chunkH = endY - startY;

                int[][] chunkData = new int[chunkH][chunkW];
                for (int y = 0; y < chunkH; y++) {
                    for (int x = 0; x < chunkW; x++) {
                        chunkData[y][x] = map[startY + y][startX + x];
                    }
                }

                int[][] compressed = compressRLE(chunkData);
                chunks.add(new MapChunk(cx, cy, startX, startY, chunkW, chunkH, compressed));
            }
        }
        return chunks;
    }

    /**
     * 从块列表还原完整地图
     */
    public static int[][] mergeChunks(List<MapChunk> chunks, int mapWidth, int mapHeight) {
        int[][] result = new int[mapHeight][mapWidth];
        for (MapChunk chunk : chunks) {
            int[][] decompressed = decompressRLE(chunk.compressedData, chunk.width, chunk.height);
            for (int y = 0; y < chunk.height; y++) {
                for (int x = 0; x < chunk.width; x++) {
                    result[chunk.startY + y][chunk.startX + x] = decompressed[y][x];
                }
            }
        }
        return result;
    }

    /**
     * 判断是否应该使用压缩传输
     */
    public static boolean shouldCompress(int mapWidth, int mapHeight) {
        return mapWidth >= COMPRESSION_THRESHOLD || mapHeight >= COMPRESSION_THRESHOLD;
    }

    /**
     * 地图块数据结构
     */
    public static class MapChunk {
        /** 块在网格中的列索引 */
        public int chunkX;
        /** 块在网格中的行索引 */
        public int chunkY;
        /** 块在地图中的起始 X 坐标 */
        public int startX;
        /** 块在地图中的起始 Y 坐标 */
        public int startY;
        /** 块宽度 */
        public int width;
        /** 块高度 */
        public int height;
        /** RLE 压缩后的数据 */
        public int[][] compressedData;

        public MapChunk() {}

        public MapChunk(int chunkX, int chunkY, int startX, int startY, int width, int height, int[][] compressedData) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.startX = startX;
            this.startY = startY;
            this.width = width;
            this.height = height;
            this.compressedData = compressedData;
        }

        /** 获取块标识 */
        public String getChunkId() {
            return chunkX + "_" + chunkY;
        }
    }
}
