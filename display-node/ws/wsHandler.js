const jwt = require('../auth/jwt');
const recorder = require('../services/recorder');

class WsHandler {
  constructor(pushService, rabbitClient) {
    this.pushService = pushService;
    this.rabbitClient = rabbitClient;
  }

  _extractUser(req) {
    try {
      const url = new URL(req.url, 'http://localhost');
      const token = url.searchParams.get('token');
      if (!token) return null;
      const payload = jwt.verify(token);
      return payload ? { username: payload.sub, role: payload.role } : null;
    } catch (e) { return null; }
  }

  onConnection(ws, req) {
    const user = this._extractUser(req);
    if (!user) {
      ws.close(4001, '认证失败');
      return;
    }

    ws._user = user;
    this.pushService.addSession(ws);
    console.log(`[WS] 客户端连接 user=${user.username} role=${user.role}`);

    ws.on('message', (data) => this._handleMessage(ws, data));
    ws.on('close', () => {
      this.pushService.removeSession(ws);
      console.log(`[WS] 客户端断开 user=${user.username}`);
    });
    ws.on('error', () => this.pushService.removeSession(ws));
  }

  _handleMessage(ws, data) {
    try {
      const msg = JSON.parse(data.toString());
      const cmd = msg.cmd;
      if (!cmd) return;

      if (cmd === 'PING') {
        if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'PONG' }));
        return;
      }

      // JOIN_SESSION: 加入已有仿真（不重置，仅订阅）
      if (cmd === 'JOIN_SESSION') {
        const sessionId = msg.data && msg.data.sessionId;
        if (sessionId) {
          if (ws._sessions) {
            ws._sessions.forEach(sid => this.pushService.unsubscribeFromSession(ws, sid));
          }
          ws._sessions = new Set([sessionId]);
          this.pushService.subscribeToSession(ws, sessionId);
          this.rabbitClient.subscribeSession(sessionId);
          this.pushService.pushLatestState(sessionId);
          console.log(`[WS] 用户 ${ws._user?.username} 加入 session ${sessionId}`);
        }
        return;
      }

      // LEAVE_SESSION: 离开仿真（不停仿真）
      if (cmd === 'LEAVE_SESSION') {
        const sessionId = msg.data && msg.data.sessionId;
        if (sessionId) {
          this.pushService.unsubscribeFromSession(ws, sessionId);
          if (ws._sessions) ws._sessions.delete(sessionId);
          console.log(`[WS] 用户 ${ws._user?.username} 离开 session ${sessionId}`);
        }
        return;
      }

      const role = ws._user ? ws._user.role : null;
      const controlCmds = ['RESUME', 'PAUSE', 'STEP_ONCE', 'SET_CONFIG', 'SET_MAP_EDIT', 'RESET'];

      if (controlCmds.includes(cmd) && role !== 'USER' && role !== 'ADMIN') {
        if (ws.readyState === 1) {
          ws.send(JSON.stringify({ type: 'ERROR', message: '权限不足' }));
        }
        return;
      }

      // STOP: 清理 session 资源
      if (cmd === 'STOP') {
        const sessionId = msg.data && msg.data.sessionId;
        if (sessionId) {
          this.pushService.unsubscribeFromSession(ws, sessionId);
          recorder.removeSession(sessionId);
          console.log(`[WS] session cleaned: ${sessionId}`);
        }
        // 继续转发 STOP 给 Controller 清理 Redis
      }

      // SET_CONFIG: 先切到新 session，取消旧订阅
      if (cmd === 'SET_CONFIG') {
        const sessionId = msg.data && msg.data.sessionId;
        if (sessionId) {
          // 清除该 WS 的所有旧 session 绑定
          if (ws._sessions) {
            ws._sessions.forEach(sid => this.pushService.unsubscribeFromSession(ws, sid));
          }
          ws._sessions = new Set([sessionId]);
          this.pushService.subscribeToSession(ws, sessionId);
          this.rabbitClient.subscribeSession(sessionId);
        }
      }

      // 控制命令带 sessionId 转发
      const dataWithUser = {
        ...(msg.data || {}),
        username: ws._user.username,
      };
      this.rabbitClient.publishCommand(cmd, dataWithUser);
      console.log(`[WS] 收到命令 → MQ user=${ws._user.username} cmd=${cmd}`);
    } catch (e) {
      console.error('[WS] 处理消息失败:', e.message);
    }
  }
}

module.exports = WsHandler;
