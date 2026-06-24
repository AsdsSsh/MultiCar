// WebSocket 处理器 — 与 Java SimulationWebSocketHandler + WebSocketAuthInterceptor 一致
const jwt = require('../auth/jwt');

class WsHandler {
  constructor(pushService, rabbitClient) {
    this.pushService = pushService;
    this.rabbitClient = rabbitClient;
  }

  /** 从 URL query 参数提取并验证 JWT */
  _extractUser(req) {
    try {
      const url = new URL(req.url, 'http://localhost');
      const token = url.searchParams.get('token');
      if (!token) return null;
      const payload = jwt.verify(token);
      return payload ? { username: payload.sub, role: payload.role } : null;
    } catch (e) {
      return null;
    }
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

    ws.on('message', (data) => {
      this._handleMessage(ws, data);
    });

    ws.on('close', () => {
      this.pushService.removeSession(ws);
      console.log(`[WS] 客户端断开 user=${user.username}`);
    });

    ws.on('error', () => {
      this.pushService.removeSession(ws);
    });
  }

  _handleMessage(ws, data) {
    try {
      const msg = JSON.parse(data.toString());
      const cmd = msg.cmd;
      if (!cmd) return;

      // 心跳
      if (cmd === 'PING') {
        if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'PONG' }));
        return;
      }

      // 权限检查
      const role = ws._user ? ws._user.role : null;
      const controlCmds = ['RESUME', 'PAUSE', 'STEP_ONCE', 'ADD_CAR', 'RESET', 'SET_CONFIG', 'SET_MAP_EDIT'];
      if (controlCmds.includes(cmd) && role !== 'USER' && role !== 'ADMIN') {
        console.warn(`[WS] 权限不足: user=${ws._user.username} role=${role} cmd=${cmd}`);
        if (ws.readyState === 1) {
          ws.send(JSON.stringify({ type: 'ERROR', message: '权限不足，只有用户角色可以执行控制操作' }));
        }
        return;
      }

      // 转发到 MQ
      this.rabbitClient.publishCommand(cmd, msg.data);
      console.log(`[WS] 收到命令 → MQ user=${ws._user.username} cmd=${cmd}`);
    } catch (e) {
      console.error('[WS] 处理消息失败:', e.message);
    }
  }
}

module.exports = WsHandler;
