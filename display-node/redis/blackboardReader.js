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
        if (times > 20) return null;
        const delay = Math.min(times * 1000, 10000);
        return delay;
      },
    };
    this.redis = new Redis(redisConfig);
    this._connected = false;
  }

  async connect() {
    try {
      await this.redis.connect();
      this._connected = true;
      console.log(`[Redis] 已连接 ${config.redis.host}:${config.redis.port}`);
    } catch (e) {
      this._connected = false;
      console.warn(`[Redis] 连接失败: ${e.message}`);
    }
  }

  get connected() { return this._connected; }

  _k(sessionId, key) { return `session:${sessionId}:${key}`; }

  async readTaskConfig(sessionId) {
    const entries = await this.redis.hgetall(this._k(sessionId, 'TaskConfig'));
    const result = {};
    for (const [key, val] of Object.entries(entries)) {
      const num = Number(val);
      result[key] = isNaN(num) ? val : (val.includes('.') ? parseFloat(val) : num);
    }
    return result;
  }

  async isPaused(sessionId) {
    const val = await this.redis.get(this._k(sessionId, 'simulation:paused'));
    return String(val) === '1';
  }

  async getSessionController(sessionId) {
    try {
      return await this.redis.get(this._k(sessionId, 'controller'));
    } catch (e) { return null; }
  }

  async scanCarIds(sessionId) {
    const prefix = `session:${sessionId}:`;
    const keys = await this.redis.keys(prefix + 'Car*:Status');
    return keys.map(k => {
      const afterPrefix = k.substring(prefix.length);
      return afterPrefix.replace(':Status', '');
    }).sort();
  }

  async readAllCars(sessionId) {
    const carIds = await this.scanCarIds(sessionId);
    const cars = [];
    for (const carId of carIds) {
      const car = { carId };
      const prefix = this._k(sessionId, '');

      const posStr = await this.redis.get(prefix + carId + ':Position');
      if (posStr && posStr.startsWith('{')) {
        try { const pos = JSON.parse(posStr); car.x = pos.x || 0; car.y = pos.y || 0; } catch (e) { }
      }
      if (car.x === undefined) { car.x = 0; car.y = 0; }

      car.status = (await this.redis.get(prefix + carId + ':Status')) || 'IDLE';

      const targetStr = await this.redis.get(prefix + carId + ':Target');
      if (targetStr && targetStr.startsWith('{')) {
        try { const t = JSON.parse(targetStr); car.targetX = t.x; car.targetY = t.y; } catch (e) { }
      }

      const routeRaw = await this.redis.lrange(prefix + carId + ':RouteList', 0, -1);
      const route = [];
      if (routeRaw) {
        for (const r of routeRaw) {
          const s = String(r).trim();
          if (s.startsWith('{')) {
            try { const pt = JSON.parse(s); route.push([pt.x || 0, pt.y || 0]); } catch (e) { }
          } else if (s.startsWith('[')) {
            try { const arr = JSON.parse(s); if (Array.isArray(arr) && arr.length >= 2) route.push([Number(arr[0]), Number(arr[1])]); } catch (e) { }
          }
        }
      }
      car.route = route;
      car.steps = parseInt(await this.redis.get(prefix + carId + ':Steps')) || 0;
      cars.push(car);
    }
    return cars;
  }

  async readMapView(sessionId, width, height) {
    return this._readBitmap(this._k(sessionId, 'mapView'), width, height);
  }

  async readMapBlock(sessionId, width, height) {
    return this._readBitmap(this._k(sessionId, 'mapBlock'), width, height);
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

  async close() { await this.redis.quit(); }
}

module.exports = BlackboardReader;
