// POST /api/auth/login, /api/auth/register, GET /api/auth/whoami
// 与 Java AuthController 完全一致
const express = require('express');
const router = express.Router();
const store = require('../store/jsonFileStore');
const passwordUtil = require('../auth/password');
const jwt = require('../auth/jwt');

function ok(data) {
  return { success: true, ...data };
}

function error(message, status) {
  const err = new Error(message);
  err.status = status || 400;
  return err;
}

// POST /api/auth/login
router.post('/login', (req, res) => {
  const { username, password } = req.body || {};
  if (!username || !password || !username.trim() || !password.trim()) {
    return res.status(400).json({ success: false, message: '用户名和密码不能为空' });
  }

  const user = store.findByUsername(username);
  if (!user) {
    return res.status(401).json({ success: false, message: '用户名或密码错误' });
  }
  if (!user.approved) {
    return res.status(403).json({ success: false, message: '账户尚未通过审批，请等待管理员审核' });
  }
  if (!passwordUtil.verify(password, user.salt, user.passwordHash)) {
    return res.status(401).json({ success: false, message: '用户名或密码错误' });
  }

  const token = jwt.generate(user.username, user.role);
  console.log(`用户登录: ${username} (角色: ${user.role})`);
  res.json(ok({ token, username: user.username, role: user.role, userId: user.id }));
});

// POST /api/auth/register
router.post('/register', (req, res) => {
  const { username, password, confirmPassword, role } = req.body || {};

  if (!username || !username.trim()) {
    return res.status(400).json({ success: false, message: '用户名不能为空' });
  }
  if (username.length < 3 || username.length > 32) {
    return res.status(400).json({ success: false, message: '用户名长度需在 3-32 个字符之间' });
  }
  if (!/^[a-zA-Z0-9_\u4e00-\u9fa5]+$/.test(username)) {
    return res.status(400).json({ success: false, message: '用户名只能包含字母、数字、下划线和中文' });
  }
  if (!password || password.length < 6) {
    return res.status(400).json({ success: false, message: '密码长度不能少于 6 个字符' });
  }
  if (password !== confirmPassword) {
    return res.status(400).json({ success: false, message: '两次输入的密码不一致' });
  }
  if (!role || !['ADMIN', 'CONFIGURATOR', 'USER'].includes(role)) {
    return res.status(400).json({ success: false, message: '无效的用户权限，可选：ADMIN, CONFIGURATOR, USER' });
  }

  try {
    store.createRegistration(username, password, role);
    res.json(ok({ message: '注册请求已提交，请等待管理员审批' }));
  } catch (e) {
    res.status(409).json({ success: false, message: e.message });
  }
});

// GET /api/auth/whoami
router.get('/whoami', (req, res) => {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ success: false, message: '未登录' });
  }

  const payload = jwt.verify(authHeader.substring(7));
  if (!payload) {
    return res.status(401).json({ success: false, message: '登录已过期，请重新登录' });
  }

  const user = store.findByUsername(payload.sub);
  if (!user) {
    return res.status(401).json({ success: false, message: '用户不存在' });
  }

  res.json(ok({ username: user.username, role: user.role, userId: user.id, approved: user.approved }));
});

module.exports = router;
