// 地图配置路由
const express = require('express');
const router = express.Router();
const store = require('../store/jsonFileStore');
const { requireConfigurator } = require('../auth/middleware');

// GET /api/config/list — 无需认证
router.get('/list', async (req, res) => {
  const maps = await store.listMaps();
  res.json({ success: true, maps });
});

// GET /api/config/get — 返回首个地图或默认配置，无需认证
router.get('/get', async (req, res) => {
  const maps = await store.listMaps();
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
router.get('/get/:mapId', async (req, res) => {
  const map = await store.getMap(req.params.mapId);
  if (map) {
    res.json({ success: true, config: map });
  } else {
    res.status(404).json({ success: false, message: '地图不存在' });
  }
});

// 以下需要 CONFIGURATOR 或 ADMIN 角色
router.use(requireConfigurator);

// POST /api/config/save
router.post('/save', async (req, res) => {
  const { name, ...data } = req.body || {};
  const mapName = (name && name.trim()) ? name.trim() : '未命名地图_' + (Date.now() % 100000);
  const saved = await store.saveMap(mapName, data, req.user.username);
  console.log(`配置员 ${req.user.username} 保存了地图: ${mapName}`);

  const pushService = req.app.get('pushService');
  if (pushService) pushService.broadcastMapUpdated();

  res.json({ success: true, message: '地图已保存', map: saved });
});

// PUT /api/config/:mapId
router.put('/:mapId', async (req, res) => {
  const ok = await store.updateMap(req.params.mapId, req.body || {});
  if (ok) {
    const pushService = req.app.get('pushService');
    if (pushService) pushService.broadcastMapUpdated();
    res.json({ success: true, message: '地图已更新' });
  } else {
    res.status(404).json({ success: false, message: '地图不存在' });
  }
});

// DELETE /api/config/:mapId
router.delete('/:mapId', async (req, res) => {
  const ok = await store.deleteMap(req.params.mapId);
  if (ok) {
    const pushService = req.app.get('pushService');
    if (pushService) pushService.broadcastMapUpdated();
    res.json({ success: true, message: '地图已删除' });
  } else {
    res.status(404).json({ success: false, message: '地图不存在' });
  }
});

module.exports = router;
