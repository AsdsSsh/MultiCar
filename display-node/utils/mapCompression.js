// 地图 RLE 压缩 — 与 Java MapCompression 一致
const CHUNK_SIZE = 16;

/** 当总格子数超过 1200 (约 40×30) 时启用压缩 */
function shouldCompress(width, height) {
  return width * height > 1200;
}

/** RLE 行程编码压缩（位打包格式：code = (count << 1) | value，与前端解压一致） */
function compressRLE(map) {
  if (!map || map.length === 0) return [];
  const result = [];
  for (let y = 0; y < map.length; y++) {
    const row = [];
    if (map[y]) {
      let count = 1;
      for (let x = 1; x <= map[y].length; x++) {
        if (x < map[y].length && map[y][x] === map[y][x - 1]) {
          count++;
        } else {
          row.push((count << 1) | (map[y][x - 1] & 1));
          count = 1;
        }
      }
    }
    result.push(row);
  }
  return result;
}

module.exports = { CHUNK_SIZE, shouldCompress, compressRLE };
