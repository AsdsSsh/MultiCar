// Express 认证中间件 — 与 Java AuthInterceptor 一致
const jwt = require('./jwt');

/** 从 Authorization header 提取并验证 JWT，注入 req.user */
function authMiddleware(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    req.user = null;
    return next();
  }

  const token = authHeader.substring(7);
  const payload = jwt.verify(token);
  if (payload) {
    req.user = { username: payload.sub, role: payload.role };
  } else {
    req.user = null;
  }
  next();
}

/** 需要 ADMIN 角色 */
function requireAdmin(req, res, next) {
  if (!req.user) return res.status(401).json({ success: false, message: '未登录' });
  if (req.user.role !== 'ADMIN')
    return res.status(403).json({ success: false, message: '需要管理员权限' });
  next();
}

/** 需要 CONFIGURATOR 或 ADMIN 角色 */
function requireConfigurator(req, res, next) {
  if (!req.user) return res.status(401).json({ success: false, message: '未登录' });
  if (req.user.role !== 'CONFIGURATOR' && req.user.role !== 'ADMIN')
    return res.status(403).json({ success: false, message: '需要配置员权限' });
  next();
}

module.exports = { authMiddleware, requireAdmin, requireConfigurator };
