// 推送服务 — 与 Java PushService 完全一致
const { shouldCompress, compressRLE } = require('../utils/mapCompression');

class PushService {
  constructor(blackboardReader) {
    this.reader = blackboardReader;
    this.sessions = new Set();
  }

  addSession(ws) {
    this.sessions.add(ws);
  }

  removeSession(ws) {
    this.sessions.delete(ws);
  }

  broadcastMapUpdated() {
    if (this.sessions.size === 0) return;
    const msg = JSON.stringify({ type: 'MAP_LIST_UPDATED' });
    for (const ws of this.sessions) {
      if (ws.readyState === 1) {
        try { ws.send(msg); } catch (e) { /* ignore */ }
      }
    }
    console.log(`[PUSH] 广播 MAP_LIST_UPDATED 到 ${this.sessions.size} 个客户端`);
  }

  async pushStateUpdate(tick) {
    if (this.sessions.size === 0) return;

    try {
      const config = await this.reader.readTaskConfig();
      const w = config.mapWidth || 40;
      const h = config.mapHeight || 30;

      const state = {
        type: 'STATE_UPDATE',
        tick,
        running: !(await this.reader.isPaused()),
        config,
        cars: await this.reader.readAllCars(),
        mapWidth: w,
        mapHeight: h,
      };

      // 地图压缩
      const useCompression = shouldCompress(w, h);
      if (useCompression) {
        const mapViewRaw = await this.reader.readMapView(w, h);
        const mapBlockRaw = await this.reader.readMapBlock(w, h);
        state.mapViewCompressed = compressRLE(mapViewRaw);
        state.mapBlockCompressed = compressRLE(mapBlockRaw);
        state.mapView = null;
        state.mapBlock = null;
        state.compressed = true;
      } else {
        state.mapView = await this.reader.readMapView(w, h);
        state.mapBlock = await this.reader.readMapBlock(w, h);
        state.compressed = false;
      }

      const json = JSON.stringify(state);
      for (const ws of this.sessions) {
        if (ws.readyState === 1) {
          try { ws.send(json); } catch (e) { /* ignore */ }
        }
      }
    } catch (e) {
      console.error('[PUSH] 组装 STATE_UPDATE 失败:', e.message);
    }
  }
}

module.exports = PushService;
