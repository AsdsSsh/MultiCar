// 回放 API
const express = require('express');
const router = express.Router();
const recorder = require('../services/recorder');

// GET /api/replay/sessions — 列出所有录制
router.get('/sessions', (req, res) => {
  res.json({ success: true, sessions: recorder.listSessions() });
});

// GET /api/replay/sessions/:sessionId — 获取全部 tick
router.get('/sessions/:sessionId', (req, res) => {
  const ticks = recorder.getSessionTicks(req.params.sessionId);
  if (ticks === null) {
    return res.status(404).json({ success: false, message: '录制不存在' });
  }
  res.json({ success: true, sessionId: req.params.sessionId, ticks, tickCount: ticks.length });
});

// GET /api/replay/sessions/:sessionId/range?from=0&to=100
router.get('/sessions/:sessionId/range', (req, res) => {
  const from = parseInt(req.query.from) || 0;
  const to = parseInt(req.query.to) || Infinity;
  const ticks = recorder.getSessionTicks(req.params.sessionId, from, to);
  if (ticks === null) {
    return res.status(404).json({ success: false, message: '录制不存在' });
  }
  res.json({ success: true, sessionId: req.params.sessionId, ticks, from, to });
});

module.exports = router;
