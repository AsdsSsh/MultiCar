const express = require('express');
const router = express.Router();

router.get('/status', (req, res) => {
  const sd = req.app.get('serviceDiscovery');
  res.json({ success: true, services: sd ? sd.getSnapshot() : {} });
});

module.exports = router;
