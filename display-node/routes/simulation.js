// 仿真管理路由
const express = require('express');
const router = express.Router();
const store = require('../store/jsonFileStore');

// 所有仿真路由需要登录
router.use((req, res, next) => {
  if (!req.user) return res.status(401).json({ success: false, message: '未登录' });
  next();
});

// GET /api/simulation/list
router.get('/list', (req, res) => {
  const simulations = store.listSimulations();
  res.json({ success: true, simulations });
});

// POST /api/simulation/create
router.post('/create', (req, res) => {
  const { name, mapId } = req.body || {};
  if (!name || !name.trim()) {
    return res.status(400).json({ success: false, message: '仿真名称不能为空' });
  }
  if (!mapId) {
    return res.status(400).json({ success: false, message: '请选择地图' });
  }
  const map = store.getMap(mapId);
  if (!map) {
    return res.status(404).json({ success: false, message: '地图不存在' });
  }
  const simulation = store.createSimulation(name.trim(), mapId, req.user.username);
  const ps = req.app.get('pushService');
  if (ps) ps.broadcastSimulationUpdated();
  res.json({ success: true, simulation });
});

// GET /api/simulation/:id
router.get('/:id', (req, res) => {
  const sim = store.getSimulation(req.params.id);
  if (!sim) return res.status(404).json({ success: false, message: '仿真不存在' });
  res.json({ success: true, simulation: sim });
});

// POST /api/simulation/:id/start
router.post('/:id/start', (req, res) => {
  const ok = store.updateSimulationStatus(req.params.id, 'running');
  if (!ok) return res.status(404).json({ success: false, message: '仿真不存在' });
  res.json({ success: true, message: '仿真已启动' });
});

// POST /api/simulation/:id/stop
router.post('/:id/stop', (req, res) => {
  const ok = store.updateSimulationStatus(req.params.id, 'inactive');
  if (!ok) return res.status(404).json({ success: false, message: '仿真不存在' });
  res.json({ success: true, message: '仿真已停止' });
});

// DELETE /api/simulation/:id
router.delete('/:id', (req, res) => {
  const sim = store.getSimulation(req.params.id);
  if (!sim) return res.status(404).json({ success: false, message: '仿真不存在' });
  if (sim.createdBy !== req.user.username && req.user.role !== 'ADMIN') {
    return res.status(403).json({ success: false, message: '无权删除此仿真' });
  }
  store.deleteSimulation(req.params.id);
  const ps = req.app.get('pushService');
  if (ps) ps.broadcastSimulationUpdated();
  res.json({ success: true, message: '仿真已删除' });
});

module.exports = router;
