const { shouldCompress, compressRLE } = require('../utils/mapCompression');
const recorder = require('./recorder');

class PushService {
  constructor(blackboardReader) {
    this.reader = blackboardReader;
    this.sessions = new Map();
    this.recordedSessions = new Set();
    this.lastTickPerSession = new Map();
  }

  addSession(ws) {
    this.sessions.set(ws, new Set());
  }

  removeSession(ws) {
    this.sessions.delete(ws);
  }

  /** WebSocket 关联到某个 session */
  subscribeToSession(ws, sessionId) {
    const set = this.sessions.get(ws);
    if (set) set.add(sessionId);
    console.log(`[WS] session bind: ${sessionId}`);
  }

  /** WebSocket 取消关联 */
  unsubscribeFromSession(ws, sessionId) {
    const set = this.sessions.get(ws);
    if (set) set.delete(sessionId);
  }

  broadcastMapUpdated() {
    const msg = JSON.stringify({ type: 'MAP_LIST_UPDATED' });
    for (const ws of this.sessions.keys()) {
      if (ws.readyState === 1) {
        try { ws.send(msg); } catch (e) { }
      }
    }
  }

  broadcastSimulationUpdated() {
    const msg = JSON.stringify({ type: 'SIMULATION_LIST_UPDATED' });
    for (const ws of this.sessions.keys()) {
      if (ws.readyState === 1) {
        try { ws.send(msg); } catch (e) { }
      }
    }
  }

  async pushStateUpdate(tick, sessionId) {
    try {
      const config = await this.reader.readTaskConfig(sessionId);
      const w = config.mapWidth || 40;
      const h = config.mapHeight || 30;

      const state = {
        type: 'STATE_UPDATE',
        sessionId,
        tick,
        running: !(await this.reader.isPaused(sessionId)),
        config,
        cars: await this.reader.readAllCars(sessionId),
        mapWidth: w,
        mapHeight: h,
      };

      const useCompression = shouldCompress(w, h);
      if (useCompression) {
        state.mapViewCompressed = compressRLE(await this.reader.readMapView(sessionId, w, h));
        state.mapBlockCompressed = compressRLE(await this.reader.readMapBlock(sessionId, w, h));
        state.mapView = null;
        state.mapBlock = null;
        state.compressed = true;
      } else {
        state.mapView = await this.reader.readMapView(sessionId, w, h);
        state.mapBlock = await this.reader.readMapBlock(sessionId, w, h);
        state.compressed = false;
      }

      const json = JSON.stringify(state);

      this.lastTickPerSession.set(sessionId, tick);

      for (const [ws, sessionSet] of this.sessions.entries()) {
        if (ws.readyState === 1 && sessionSet.has(sessionId)) {
          try { ws.send(json); } catch (e) { }
        }
      }

      // 自动录制：tick==1 开始，持续录制直到 session 结束
      if (!this.recordedSessions.has(sessionId)) {
        recorder.startRecording(sessionId);
        this.recordedSessions.add(sessionId);
      }
      recorder.recordTick(sessionId, json);
    } catch (e) {
      console.error(`[PUSH] 组装 STATE_UPDATE 失败 (session ${sessionId}):`, e.message);
    }
  }

  async pushLatestState(sessionId) {
    const tick = this.lastTickPerSession.get(sessionId) || 0;
    await this.pushStateUpdate(tick, sessionId);
  }
}

module.exports = PushService;
