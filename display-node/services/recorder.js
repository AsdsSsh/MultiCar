// 仿真录制器 — JSONL 格式，每行一个 tick 快照
const fs = require('fs');
const path = require('path');

const REPLAY_DIR = path.join(__dirname, '..', 'data', 'replays');
const MAX_TICKS = 5000;

class Recorder {
  constructor() {
    if (!fs.existsSync(REPLAY_DIR)) {
      fs.mkdirSync(REPLAY_DIR, { recursive: true });
    }
    this.streams = new Map(); // sessionId → { stream, tickCount, startTime }
  }

  startRecording(sessionId) {
    if (this.streams.has(sessionId)) return;
    const file = path.join(REPLAY_DIR, `${sessionId}.jsonl`);
    const stream = fs.createWriteStream(file, { flags: 'w' });
    this.streams.set(sessionId, { stream, tickCount: 0, startTime: Date.now() });
    console.log(`[REC] 开始录制 session ${sessionId}`);
  }

  recordTick(sessionId, json) {
    const s = this.streams.get(sessionId);
    if (!s) return;
    if (s.tickCount >= MAX_TICKS) {
      this.stopRecording(sessionId);
      return;
    }
    s.stream.write(json + '\n');
    s.tickCount++;
  }

  stopRecording(sessionId) {
    const s = this.streams.get(sessionId);
    if (!s) return;
    s.stream.end();
    this.streams.delete(sessionId);
    console.log(`[REC] 结束录制 session ${sessionId}，共 ${s.tickCount} tick`);
  }

  removeSession(sessionId) {
    const file = path.join(REPLAY_DIR, `${sessionId}.jsonl`);
    try {
      if (fs.existsSync(file)) fs.unlinkSync(file);
    } catch (e) { /* ignore */ }
  }

  /** 列出所有录制 */
  listSessions() {
    if (!fs.existsSync(REPLAY_DIR)) return [];
    return fs.readdirSync(REPLAY_DIR)
      .filter(f => f.endsWith('.jsonl'))
      .map(f => {
        const sessionId = f.replace('.jsonl', '');
        const stat = fs.statSync(path.join(REPLAY_DIR, f));
        // 读最后一行获取 tick 总数
        const content = fs.readFileSync(path.join(REPLAY_DIR, f), 'utf8');
        const lines = content.trim().split('\n').filter(Boolean);
        let config = {}, tickCount = lines.length;
        if (lines.length > 0) {
          try { const first = JSON.parse(lines[0]); config = first.config || {}; } catch (e) {}
        }
        return {
          sessionId,
          tickCount,
          startTime: stat.birthtimeMs || stat.ctimeMs,
          config: { mapWidth: config.mapWidth, mapHeight: config.mapHeight, carCount: config.carCount },
        };
      })
      .sort((a, b) => b.startTime - a.startTime);
  }

  /** 获取全部 tick 数据 */
  getSessionTicks(sessionId, from = 0, to = Infinity) {
    const file = path.join(REPLAY_DIR, `${sessionId}.jsonl`);
    if (!fs.existsSync(file)) return null;
    const content = fs.readFileSync(file, 'utf8');
    const lines = content.trim().split('\n').filter(Boolean);
    const slice = lines.slice(from, to);
    return slice.map(line => {
      try { return JSON.parse(line); } catch (e) { return null; }
    }).filter(Boolean);
  }
}

module.exports = new Recorder();
