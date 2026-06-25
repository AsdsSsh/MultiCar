// Redis 持久化 — 多机共享黑板
const fs = require('fs');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const Redis = require('ioredis');
const passwordUtil = require('../auth/password');
const cfg = require('../config');

const DATA_DIR = path.join(__dirname, '..', 'data');

const USERS_REDIS_KEY = 'users';
const REGISTRATIONS_REDIS_KEY = 'registrations';
const MAPS_REDIS_KEY = 'maps';
const SIMULATIONS_REDIS_KEY = 'simulations';

class JsonFileStore {
  constructor() {
    this.users = new Map();
    this.registrations = new Map();
    this.savedMaps = new Map();
    this.simulations = new Map();

    if (!fs.existsSync(DATA_DIR)) {
      fs.mkdirSync(DATA_DIR, { recursive: true });
    }

    this.redis = new Redis({
      host: cfg.redis.host,
      port: cfg.redis.port,
      password: cfg.redis.password,
      lazyConnect: true,
    });
  }

  // ==================== 初始化 ====================

  async initRedis() {
    await this.redis.connect();
    console.log('store Redis 已连接');

    // 加载用户
    const allUsers = await this.redis.hgetall(USERS_REDIS_KEY);
    if (allUsers) {
      for (const [id, json] of Object.entries(allUsers)) {
        try { this.users.set(id, JSON.parse(json)); } catch (e) { /* skip */ }
      }
    }
    if (this.users.size === 0) {
      await this._createDefaultAdmin();
    }

    // 加载注册请求
    const allRegs = await this.redis.hgetall(REGISTRATIONS_REDIS_KEY);
    if (allRegs) {
      for (const [id, json] of Object.entries(allRegs)) {
        try { this.registrations.set(id, JSON.parse(json)); } catch (e) { /* skip */ }
      }
    }

    // 加载地图
    const allMaps = await this.redis.hgetall(MAPS_REDIS_KEY);
    if (allMaps) {
      for (const [id, json] of Object.entries(allMaps)) {
        try { this.savedMaps.set(id, JSON.parse(json)); } catch (e) { /* skip */ }
      }
    }

    // 加载仿真
    const allSims = await this.redis.hgetall(SIMULATIONS_REDIS_KEY);
    if (allSims) {
      for (const [id, json] of Object.entries(allSims)) {
        try { this.simulations.set(id, JSON.parse(json)); } catch (e) { /* skip */ }
      }
    }

    console.log(
      `JsonFileStore 初始化完成：${this.users.size} 用户, ${this.registrations.size} 注册, ${this.savedMaps.size} 地图, ${this.simulations.size} 仿真`
    );
  }

  async _createDefaultAdmin() {
    const salt = passwordUtil.generateSalt();
    const hash = passwordUtil.hash('admin123', salt);
    const admin = {
      id: 'admin001', username: 'admin',
      passwordHash: hash, salt,
      role: 'ADMIN', approved: true,
      createdAt: Date.now(), approvedAt: Date.now(),
    };
    this.users.set(admin.id, admin);
    await this.redis.hset(USERS_REDIS_KEY, admin.id, JSON.stringify(admin));
    console.log('创建默认管理员: admin / admin123');
  }

  // ==================== Redis 同步 ====================

  async _syncUser(id) {
    try {
      const u = this.users.get(id);
      if (u) await this.redis.hset(USERS_REDIS_KEY, id, JSON.stringify(u));
      else await this.redis.hdel(USERS_REDIS_KEY, id);
    } catch (e) { /* */ }
  }

  async _syncReg(id) {
    try {
      const r = this.registrations.get(id);
      if (r) await this.redis.hset(REGISTRATIONS_REDIS_KEY, id, JSON.stringify(r));
      else await this.redis.hdel(REGISTRATIONS_REDIS_KEY, id);
    } catch (e) { /* */ }
  }

  async _syncMap(id) {
    try {
      const m = this.savedMaps.get(id);
      if (m) await this.redis.hset(MAPS_REDIS_KEY, id, JSON.stringify(m));
      else await this.redis.hdel(MAPS_REDIS_KEY, id);
    } catch (e) { /* */ }
  }

  async _syncSim(id) {
    try {
      const s = this.simulations.get(id);
      if (s) await this.redis.hset(SIMULATIONS_REDIS_KEY, id, JSON.stringify(s));
      else await this.redis.hdel(SIMULATIONS_REDIS_KEY, id);
    } catch (e) { /* */ }
  }

  // ==================== 用户管理 ====================

  findByUsername(username) {
    for (const u of this.users.values()) {
      if (u.username === username) return u;
    }
    return null;
  }

  findById(id) { return this.users.get(id) || null; }
  listAllUsers() { return Array.from(this.users.values()); }

  createUser(username, plainPassword, role, approved) {
    if (this.findByUsername(username)) throw new Error('用户名已存在: ' + username);
    const id = uuidv4().substring(0, 8);
    const salt = passwordUtil.generateSalt();
    const user = {
      id, username,
      passwordHash: passwordUtil.hash(plainPassword, salt), salt,
      role, approved,
      createdAt: Date.now(),
      approvedAt: approved ? Date.now() : null,
    };
    this.users.set(id, user);
    this._syncUser(id);
    console.log(`创建用户: ${username} (角色: ${role})`);
    return user;
  }

  resetPassword(userId, newPassword) {
    const user = this.users.get(userId);
    if (!user) return false;
    user.salt = passwordUtil.generateSalt();
    user.passwordHash = passwordUtil.hash(newPassword, user.salt);
    this._syncUser(userId);
    console.log(`重置密码: ${user.username}`);
    return true;
  }

  deleteUser(userId) {
    const removed = this.users.get(userId);
    if (!removed) return false;
    this.users.delete(userId);
    this._syncUser(userId);
    console.log(`删除用户: ${removed.username}`);
    return true;
  }

  // ==================== 注册请求管理 ====================

  createRegistration(username, plainPassword, requestedRole) {
    for (const r of this.registrations.values()) {
      if (r.username === username && r.status === 'PENDING')
        throw new Error('已有待审批的注册请求: ' + username);
    }
    if (this.findByUsername(username)) throw new Error('用户名已存在: ' + username);
    const id = uuidv4().substring(0, 8);
    const req = {
      id, username,
      passwordHash: passwordUtil.hash(plainPassword, passwordUtil.generateSalt()),
      salt: passwordUtil.generateSalt(),
      requestedRole, status: 'PENDING',
      createdAt: Date.now(), reviewedBy: null, reviewedAt: null,
    };
    this.registrations.set(id, req);
    this._syncReg(id);
    console.log(`创建注册请求: ${username} (申请角色: ${requestedRole})`);
    return req;
  }

  listRegistrations() { return Array.from(this.registrations.values()); }

  approveRegistration(requestId, assignedRole, reviewer) {
    const req = this.registrations.get(requestId);
    if (!req) throw new Error('注册请求不存在: ' + requestId);
    if (req.status !== 'PENDING') throw new Error('该请求已处理');
    req.status = 'APPROVED'; req.reviewedBy = reviewer; req.reviewedAt = Date.now();
    this._syncReg(requestId);

    const userId = uuidv4().substring(0, 8);
    const user = {
      id: userId, username: req.username,
      passwordHash: req.passwordHash, salt: req.salt,
      role: assignedRole || req.requestedRole, approved: true,
      createdAt: Date.now(), approvedAt: Date.now(),
    };
    this.users.set(userId, user);
    this._syncUser(userId);
    console.log(`审批通过注册: ${req.username} → 角色: ${user.role}`);
    return user;
  }

  rejectRegistration(requestId, reviewer) {
    const req = this.registrations.get(requestId);
    if (!req) throw new Error('注册请求不存在: ' + requestId);
    if (req.status !== 'PENDING') throw new Error('该请求已处理');
    req.status = 'REJECTED'; req.reviewedBy = reviewer; req.reviewedAt = Date.now();
    this._syncReg(requestId);
    console.log(`拒绝注册: ${req.username}`);
  }

  // ==================== 地图管理 ====================

  listMaps() { return Array.from(this.savedMaps.values()); }

  getMap(mapId) {
    const map = this.savedMaps.get(mapId);
    return map ? { ...map } : null;
  }

  saveMap(name, data, createdBy) {
    const id = uuidv4().substring(0, 8);
    const map = {
      id, name,
      mapWidth: data.mapWidth || 40, mapHeight: data.mapHeight || 30,
      carCount: data.carCount || 5, obstacleDensity: data.obstacleDensity || 0.2,
      algorithm: data.algorithm || 'A_STAR',
      obstacles: data.obstacles || [], carPositions: data.carPositions || [],
      createdAt: Date.now(), createdBy,
      ...data, id,
    };
    this.savedMaps.set(id, map);
    this._syncMap(id);
    console.log(`保存地图: ${name} (${id}), 创建者: ${createdBy}`);
    return { ...map };
  }

  updateMap(mapId, data) {
    const existing = this.savedMaps.get(mapId);
    if (!existing) return false;
    Object.assign(existing, data, { updatedAt: Date.now() });
    this._syncMap(mapId);
    console.log(`更新地图: ${mapId}`);
    return true;
  }

  deleteMap(mapId) {
    const removed = this.savedMaps.get(mapId);
    if (!removed) return false;
    this.savedMaps.delete(mapId);
    this._syncMap(mapId);
    console.log(`删除地图: ${removed.name} (${mapId})`);
    return true;
  }

  // ==================== 仿真管理 ====================

  listSimulations() {
    return Array.from(this.simulations.values()).map(sim => {
      const map = this.savedMaps.get(sim.mapId);
      return { ...sim, mapName: map ? map.name : '未知地图' };
    });
  }

  getSimulation(id) {
    const sim = this.simulations.get(id);
    return sim ? { ...sim } : null;
  }

  createSimulation(name, mapId, createdBy) {
    const id = uuidv4().substring(0, 8);
    const sim = {
      id, name, mapId, sessionId: id,
      status: 'inactive', createdAt: Date.now(),
      createdBy, updatedAt: Date.now(),
    };
    this.simulations.set(id, sim);
    this._syncSim(id);
    console.log(`创建仿真: ${name} (${id}), 创建者: ${createdBy}`);
    return { ...sim };
  }

  updateSimulationStatus(id, status) {
    const sim = this.simulations.get(id);
    if (!sim) return false;
    sim.status = status; sim.updatedAt = Date.now();
    this._syncSim(id);
    return true;
  }

  deleteSimulation(id) {
    const removed = this.simulations.get(id);
    if (!removed) return false;
    this.simulations.delete(id);
    this._syncSim(id);
    console.log(`删除仿真: ${removed.name} (${id})`);
    return true;
  }
}

module.exports = new JsonFileStore();
