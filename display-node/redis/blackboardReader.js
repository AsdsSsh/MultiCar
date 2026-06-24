// Redis 黑板数据读取 — 与 Java BlackboardReader 完全一致
const Redis = require('ioredis');
const config = require('../config');

class BlackboardReader {
  constructor() {
    this.redis = new Redis({
      host: config.redis.host,
      port: config.redis.port,
      password: config.redis.password || undefined,
      lazyConnect: true,
    });
    this.carKeyPrefix = config.carKeyPrefix;
  }

  async connect() {
    await this.redis.connect();
    console.log(`BlackboardReader 已连接 Redis: ${config.redis.host}:${config.redis.port}`);
  }

  /** 读取 TaskConfig Hash */
  async readTaskConfig() {
    const entries = await this.redis.hgetall('TaskConfig');
    const result = {};
    for (const [key, val] of Object.entries(entries)) {
      const num = Number(val);
      result[key] = isNaN(num) ? val : (val.includes('.') ? parseFloat(val) : num);
    }
    return result;
  }

  /** 读取仿真暂停状态 */
  async isPaused() {
    const val = await this.redis.get('simulation:paused');
    return String(val) === '1';
  }

  /** 扫描所有小车 ID */
  async scanCarIds() {
    const keys = await this.redis.keys(this.carKeyPrefix + '*:Status');
    return keys.map(k => k.replace(':Status', '')).sort();
  }

  /** 读取所有小车状态 */
  async readAllCars() {
    const carIds = await this.scanCarIds();
    const cars = [];

    for (const carId of carIds) {
      const car = { carId };

      // Position
      const posStr = await this.redis.get(carId + ':Position');
      if (posStr && posStr.startsWith('{')) {
        try {
          const pos = JSON.parse(posStr);
          car.x = pos.x || 0;
          car.y = pos.y || 0;
        } catch (e) { /* ignore */ }
      }
      if (car.x === undefined) car.x = 0;
      if (car.y === undefined) car.y = 0;

      // Status
      const status = await this.redis.get(carId + ':Status');
      car.status = status || 'IDLE';

      // Target
      const targetStr = await this.redis.get(carId + ':Target');
      if (targetStr && targetStr.startsWith('{')) {
        try {
          const t = JSON.parse(targetStr);
          car.targetX = t.x;
          car.targetY = t.y;
        } catch (e) { /* ignore */ }
      }

      // RouteList
      const routeRaw = await this.redis.lrange(carId + ':RouteList', 0, -1);
      const route = [];
      if (routeRaw) {
        for (const r of routeRaw) {
          const s = String(r).trim();
          if (s.startsWith('{')) {
            try {
              const pt = JSON.parse(s);
              route.push([pt.x || 0, pt.y || 0]);
            } catch (e) { /* ignore */ }
          } else if (s.startsWith('[')) {
            try {
              const arr = JSON.parse(s);
              if (Array.isArray(arr) && arr.length >= 2) {
                route.push([Number(arr[0]) || 0, Number(arr[1]) || 0]);
              }
            } catch (e) { /* ignore */ }
          }
        }
      }
      car.route = route;

      // Steps
      const steps = await this.redis.get(carId + ':Steps');
      car.steps = steps ? parseInt(steps, 10) : 0;

      cars.push(car);
    }
    return cars;
  }

  /** 读取 mapView Bitmap → int[][] */
  async readMapView(width, height) {
    return this._readBitmap('mapView', width, height);
  }

  /** 读取 mapBlock Bitmap → int[][] */
  async readMapBlock(width, height) {
    return this._readBitmap('mapBlock', width, height);
  }

  /** 读取 Bitmap 原始字节并解析为二维数组 */
  async _readBitmap(key, width, height) {
    const buf = await this.redis.getBuffer(key);
    const result = [];
    for (let y = 0; y < height; y++) {
      const row = [];
      for (let x = 0; x < width; x++) {
        const bitIndex = y * width + x;
        const byteIdx = bitIndex >>> 3;       // Math.floor(bitIndex / 8)
        const bitOffset = 7 - (bitIndex & 7); // 7 - (bitIndex % 8)
        if (buf && byteIdx < buf.length) {
          row.push((buf[byteIdx] >>> bitOffset) & 1);
        } else {
          row.push(0);
        }
      }
      result.push(row);
    }
    return result;
  }

  async close() {
    await this.redis.quit();
  }
}

module.exports = BlackboardReader;
