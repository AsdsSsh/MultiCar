// 地图配置路由 — 与 Java MapConfigController 完全一致
const express = require('express');
const router = express.Router();
const store = require('../store/jsonFileStore');
const { requireConfigurator } = require('../auth/middleware');

// GET /api/config/list — 无需认证
router.get('/list', (req, res) => {
  const maps = store.listMaps();
  res.json({ success: true, maps });
});

// GET /api/config/get — 返回首个地图或默认配置，无需认证
router.get('/get', (req, res) => {
  const maps = store.listMaps();
  if (maps.length > 0) {
    res.json({ success: true, config: maps[0] });
  } else {
    res.json({
      success: true,
      config: { mapWidth: 40, mapHeight: 30, carCount: 5, obstacleDensity: 0.2, algorithm: 'A_STAR' },
      message: '暂无地图配置，使用默认值',
    });
  }
});

// GET /api/config/get/:mapId — 无需认证
router.get('/get/:mapId', (req, res) => {
  const map = store.getMap(req.params.mapId);
  if (map) {
    res.json({ success: true, config: map });
  } else {
    res.status(404).json({ success: false, message: '地图不存在' });
  }
});

// 以下需要 CONFIGURATOR 或 ADMIN 角色
router.use(requireConfigurator);

// POST /api/config/save
router.post('/save', (req, res) => {
  const { name, ...data } = req.body || {};
  const mapName = (name && name.trim()) ? name.trim() : '未命名地图_' + (Date.now() % 100000);
  const saved = store.saveMap(mapName, data, req.user.username);
  console.log(`配置员 ${req.user.username} 保存了地图: ${mapName}`);

  // 广播地图列表更新
  const pushService = req.app.get('pushService');
  if (pushService) pushService.broadcastMapUpdated();

  res.json({ success: true, message: '地图已保存', map: saved });
});

// PUT /api/config/:mapId
router.put('/:mapId', (req, res) => {
  const ok = store.updateMap(req.params.mapId, req.body || {});
  if (ok) {
    const pushService = req.app.get('pushService');
    if (pushService) pushService.broadcastMapUpdated();
    res.json({ success: true, message: '地图已更新' });
  } else {
    res.status(404).json({ success: false, message: '地图不存在' });
  }
});

// DELETE /api/config/:mapId
router.delete('/:mapId', (req, res) => {
  const ok = store.deleteMap(req.params.mapId);
  if (ok) {
    const pushService = req.app.get('pushService');
    if (pushService) pushService.broadcastMapUpdated();
    res.json({ success: true, message: '地图已删除' });
  } else {
    res.status(404).json({ success: false, message: '地图不存在' });
  }
});

module.exports = router;
