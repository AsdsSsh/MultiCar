// ============================================================
// 地图压缩存储工具（前端解压）
// 对应后端 MapCompression.java 的 RLE 压缩格式
// ============================================================

/**
 * 解压 RLE 编码的单行
 * @param {number[]} encoded - RLE 编码数组
 * @param {number} width - 目标行宽度
 * @returns {number[]} 解压后的数组
 */
export function decompressRowRLE(encoded, width) {
  const result = new Array(width)
  let pos = 0
  for (const code of encoded) {
    const bit = code & 1
    const count = code >> 1
    for (let i = 0; i < count && pos < width; i++) {
      result[pos++] = bit
    }
  }
  return result
}

/**
 * 解压完整的 RLE 压缩地图
 * @param {number[][]} compressed - RLE 编码的二维数组 [rowIndex][encodedValues...]
 * @param {number} width - 地图宽度
 * @param {number} height - 地图高度
 * @returns {number[][]} 解压后的二维数组
 */
export function decompressRLE(compressed, width, height) {
  const result = new Array(height)
  for (let y = 0; y < Math.min(height, compressed.length); y++) {
    if (compressed[y]) {
      result[y] = decompressRowRLE(compressed[y], width)
    } else {
      result[y] = new Array(width).fill(0)
    }
  }
  return result
}

/**
 * 检查地图是否使用压缩传输
 */
export function isCompressed(mapView, mapBlock) {
  // 压缩数据是 int[][] 但内部元素是压缩编码值（非0/1）
  if (!Array.isArray(mapView) || mapView.length === 0) return false
  if (!Array.isArray(mapView[0])) return false
  if (mapView[0].length === 0) return false
  // 压缩格式下，编码值通常 > 1（count >= 1, bit = 0 or 1, so code >= 2 or >= 3）
  return mapView[0][0] > 1
}
