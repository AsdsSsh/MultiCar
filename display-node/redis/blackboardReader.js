// Redis 黑板数据读取 — 与 Java BlackboardReader 完全一致
const Redis = require('ioredis');
const config = require('../config');

class BlackboardReader {
  constructor() {
    const redisConfig = {
      host: config.redis.host,
      port: config.redis.port,
      password: config.redis.password || undefined,
      lazyConnect: true,
      retryStrategy(times) {
        if (times > 20) return null; // 放弃重试
        const delay = Math.min(times * 1000, 10000);
        console.log(`[Redis] 第 ${times} 次重试，${delay / 1000}s 后重连…`);
        return delay;
      },
    };
    this.redis = new Redis(redisConfig);
    this.carKeyPrefix = config.carKeyPrefix;
    this._connected = false;
  }

  async connect() {
    try {
      await this.redis.connect();
      this._connected = true;
      console.log(`[Redis] 已连接 ${config.redis.host}:${config.redis.port}`);
    } catch (e) {
      this._connected = false;
      console.warn(`[Redis] 连接失败 (${config.redis.host}:${config.redis.port}): ${e.message}`);
      console.warn('[Redis] 服务将继续运行，Redis 恢复后自动重连');
    }
  }

  get connected() { return this._connected; }

  async readTaskConfig() {
    const entries = await this.redis.hgetall('TaskConfig');
    const result = {};
    for (const [key, val] of Object.entries(entries)) {
      const num = Number(val);
      result[key] = isNaN(num) ? val : (val.includes('.') ? parseFloat(val) : num);
    }
    return result;
  }

  async isPaused() {
    const val = await this.redis.get('simulation:paused');
    return String(val) === '1';
  }

  async scanCarIds() {
    const keys = await this.redis.keys(this.carKeyPrefix + '*:Status');
    return keys.map(k => k.replace(':Status', '')).sort();
  }

  async readAllCars() {
    const carIds = await this.scanCarIds();
    const cars = [];
    for (const carId of carIds) {
      const car = { carId };

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

      const status = await this.redis.get(carId + ':Status');
      car.status = status || 'IDLE';

      const targetStr = await this.redis.get(carId + ':Target');
      if (targetStr && targetStr.startsWith('{')) {
        try {
          const t = JSON.parse(targetStr);
          car.targetX = t.x;
          car.targetY = t.y;
        } catch (e) { /* ignore */ }
      }

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

      const steps = await this.redis.get(carId + ':Steps');
      car.steps = steps ? parseInt(steps, 10) : 0;

      cars.push(car);
    }
    return cars;
  }

  async readMapView(width, height) {
    return this._readBitmap('mapView', width, height);
  }

  async readMapBlock(width, height) {
    return this._readBitmap('mapBlock', width, height);
  }

  async _readBitmap(key, width, height) {
    const buf = await this.redis.getBuffer(key);
    const result = [];
    for (let y = 0; y < height; y++) {
      const row = [];
      for (let x = 0; x < width; x++) {
        const bitIndex = y * width + x;
        const byteIdx = bitIndex >>> 3;
        const bitOffset = 7 - (bitIndex & 7);
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
