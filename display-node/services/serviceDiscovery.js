const Redis = require('ioredis');
const cfg = require('../config');

const SCAN_INTERVAL = 1000;
const KEY_PREFIX = 'service:';

class ServiceDiscovery {
  constructor() {
    this.redis = new Redis({
      host: cfg.redis.host,
      port: cfg.redis.port,
      password: cfg.redis.password,
      lazyConnect: true,
    });
    this.snapshot = {};
    this.onChange = null;
    this._timer = null;
  }

  async start(onChange) {
    this.onChange = onChange;
    await this.redis.connect();
    console.log('ServiceDiscovery Redis 已连接');
    this._timer = setInterval(() => this._scan(), SCAN_INTERVAL);
    this._scan(); // 立即执行一次
  }

  async _scan() {
    try {
      const keys = await this.redis.keys(KEY_PREFIX + '*');
      const current = {};
      for (const key of keys) {
        try {
          const json = await this.redis.get(key);
          if (!json) continue;
          const info = JSON.parse(json);
          if (!current[info.type]) current[info.type] = [];
          current[info.type].push(info);
        } catch (e) { /* skip bad key */ }
      }
      if (JSON.stringify(current) !== JSON.stringify(this.snapshot)) {
        this.snapshot = current;
        const counts = Object.entries(current).map(([k, v]) => k + ':' + v.length).join(' ');
        console.log('[ServiceDiscovery] 发现变化:', counts || '(空)');
        if (this.onChange) this.onChange(current);
      }
    } catch (e) {
      console.error('[ServiceDiscovery] 扫描失败:', e.message);
    }
  }

  getSnapshot() { return this.snapshot; }

  stop() {
    if (this._timer) clearInterval(this._timer);
    this.redis.disconnect();
  }
}

module.exports = ServiceDiscovery;
