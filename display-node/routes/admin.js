// 管理员路由 — 与 Java AdminController 完全一致
const express = require('express');
const router = express.Router();
const store = require('../store/jsonFileStore');
const { requireAdmin } = require('../auth/middleware');

router.use(requireAdmin);

// POST /api/admin/users — 管理员直接创建用户
router.post('/users', (req, res) => {
  const { username, password, role } = req.body || {};
  if (!username || !username.trim()) {
    return res.status(400).json({ success: false, message: '用户名不能为空' });
  }
  if (username.length < 3 || username.length > 32) {
    return res.status(400).json({ success: false, message: '用户名长度需在 3-32 个字符之间' });
  }
  if (!password || password.length < 6) {
    return res.status(400).json({ success: false, message: '密码长度不能少于 6 个字符' });
  }
  if (!role || !['ADMIN', 'CONFIGURATOR', 'USER'].includes(role)) {
    return res.status(400).json({ success: false, message: '无效角色' });
  }
  try {
    const user = store.createUser(username.trim(), password, role, true);
    console.log(`管理员 ${req.user.username} 创建了用户: ${username} (${role})`);
    res.json({ success: true, message: '用户已创建', user: { id: user.id, username: user.username, role: user.role } });
  } catch (e) {
    res.status(409).json({ success: false, message: e.message });
  }
});

// GET /api/admin/users
router.get('/users', (req, res) => {
  const users = store.listAllUsers().map(u => ({
    id: u.id,
    username: u.username,
    role: u.role,
    approved: u.approved,
    createdAt: u.createdAt,
    approvedAt: u.approvedAt,
  }));
  res.json({ success: true, users });
});

// POST /api/admin/users/:id/reset-password
router.post('/users/:id/reset-password', (req, res) => {
  const { newPassword } = req.body || {};
  if (!newPassword || newPassword.length < 6) {
    return res.status(400).json({ success: false, message: '新密码长度不能少于 6 个字符' });
  }

  const user = store.findById(req.params.id);
  if (!user) {
    return res.status(404).json({ success: false, message: '用户不存在' });
  }

  const ok = store.resetPassword(req.params.id, newPassword);
  console.log(`管理员 ${req.user.username} 重置了用户 ${user.username} 的密码`);
  res.json({ success: ok, message: ok ? '密码已重置' : '重置失败' });
});

// DELETE /api/admin/users/:id
router.delete('/users/:id', (req, res) => {
  const user = store.findById(req.params.id);
  if (!user) {
    return res.status(404).json({ success: false, message: '用户不存在' });
  }
  if (user.username === req.user.username) {
    return res.status(400).json({ success: false, message: '不能删除自己的账户' });
  }

  store.deleteUser(req.params.id);
  console.log(`管理员 ${req.user.username} 删除了用户 ${user.username}`);
  res.json({ success: true, message: '用户已删除' });
});

// GET /api/admin/registrations
router.get('/registrations', (req, res) => {
  const reqs = store.listRegistrations().map(r => ({
    id: r.id,
    username: r.username,
    requestedRole: r.requestedRole,
    status: r.status,
    createdAt: r.createdAt,
    reviewedBy: r.reviewedBy,
    reviewedAt: r.reviewedAt,
  }));
  res.json({ success: true, registrations: reqs });
});

// POST /api/admin/registrations/:id/approve
router.post('/registrations/:id/approve', (req, res) => {
  const { assignedRole } = req.body || {};
  try {
    const user = store.approveRegistration(req.params.id, assignedRole, req.user.username);
    res.json({ success: true, message: '已通过审批', username: user.username, role: user.role });
  } catch (e) {
    res.status(400).json({ success: false, message: e.message });
  }
});

// POST /api/admin/registrations/:id/reject
router.post('/registrations/:id/reject', (req, res) => {
  try {
    store.rejectRegistration(req.params.id, req.user.username);
    res.json({ success: true, message: '已拒绝注册请求' });
  } catch (e) {
    res.status(400).json({ success: false, message: e.message });
  }
});

module.exports = router;
