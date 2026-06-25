// Redis 持久化 — 实时读写，支持多实例共享
const { v4: uuidv4 } = require('uuid');
const Redis = require('ioredis');
const passwordUtil = require('../auth/password');
const cfg = require('../config');

const USERS_REDIS_KEY = 'users';
const REGISTRATIONS_REDIS_KEY = 'registrations';
const MAPS_REDIS_KEY = 'maps';
const SIMULATIONS_REDIS_KEY = 'simulations';

class JsonFileStore {
  constructor() {
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

    const userCount = await this.redis.hlen(USERS_REDIS_KEY);
    if (userCount === 0) {
      await this._createDefaultAdmin();
    }

    const mapCount = await this.redis.hlen(MAPS_REDIS_KEY);
    const regCount = await this.redis.hlen(REGISTRATIONS_REDIS_KEY);
    const simCount = await this.redis.hlen(SIMULATIONS_REDIS_KEY);
    console.log(
      `JsonFileStore 初始化完成：${userCount} 用户, ${regCount} 注册, ${mapCount} 地图, ${simCount} 仿真`
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
    await this.redis.hset(USERS_REDIS_KEY, admin.id, JSON.stringify(admin));
    console.log('创建默认管理员: admin / admin123');
  }

  // ==================== 用户管理 ====================

  async findByUsername(username) {
    const all = await this.redis.hgetall(USERS_REDIS_KEY);
    for (const [, json] of Object.entries(all)) {
      try {
        const u = JSON.parse(json);
        if (u.username === username) return u;
      } catch (e) { /* skip */ }
    }
    return null;
  }

  async findById(id) {
    const json = await this.redis.hget(USERS_REDIS_KEY, id);
    return json ? JSON.parse(json) : null;
  }

  async listAllUsers() {
    const all = await this.redis.hgetall(USERS_REDIS_KEY);
    return Object.values(all).map(json => JSON.parse(json));
  }

  async createUser(username, plainPassword, role, approved) {
    const existing = await this.findByUsername(username);
    if (existing) throw new Error('用户名已存在: ' + username);
    const id = uuidv4().substring(0, 8);
    const salt = passwordUtil.generateSalt();
    const user = {
      id, username,
      passwordHash: passwordUtil.hash(plainPassword, salt), salt,
      role, approved,
      createdAt: Date.now(),
      approvedAt: approved ? Date.now() : null,
    };
    await this.redis.hset(USERS_REDIS_KEY, id, JSON.stringify(user));
    console.log(`创建用户: ${username} (角色: ${role})`);
    return user;
  }

  async resetPassword(userId, newPassword) {
    const json = await this.redis.hget(USERS_REDIS_KEY, userId);
    if (!json) return false;
    const user = JSON.parse(json);
    user.salt = passwordUtil.generateSalt();
    user.passwordHash = passwordUtil.hash(newPassword, user.salt);
    await this.redis.hset(USERS_REDIS_KEY, userId, JSON.stringify(user));
    console.log(`重置密码: ${user.username}`);
    return true;
  }

  async deleteUser(userId) {
    const json = await this.redis.hget(USERS_REDIS_KEY, userId);
    if (!json) return false;
    await this.redis.hdel(USERS_REDIS_KEY, userId);
    console.log(`删除用户: ${JSON.parse(json).username}`);
    return true;
  }

  // ==================== 注册请求管理 ====================

  async createRegistration(username, plainPassword, requestedRole) {
    const all = await this.redis.hgetall(REGISTRATIONS_REDIS_KEY);
    for (const [, json] of Object.entries(all)) {
      const r = JSON.parse(json);
      if (r.username === username && r.status === 'PENDING')
        throw new Error('已有待审批的注册请求: ' + username);
    }
    const existing = await this.findByUsername(username);
    if (existing) throw new Error('用户名已存在: ' + username);

    const id = uuidv4().substring(0, 8);
    const req = {
      id, username,
      passwordHash: passwordUtil.hash(plainPassword, passwordUtil.generateSalt()),
      salt: passwordUtil.generateSalt(),
      requestedRole, status: 'PENDING',
      createdAt: Date.now(), reviewedBy: null, reviewedAt: null,
    };
    await this.redis.hset(REGISTRATIONS_REDIS_KEY, id, JSON.stringify(req));
    console.log(`创建注册请求: ${username} (申请角色: ${requestedRole})`);
    return req;
  }

  async listRegistrations() {
    const all = await this.redis.hgetall(REGISTRATIONS_REDIS_KEY);
    return Object.values(all).map(json => JSON.parse(json));
  }

  async approveRegistration(requestId, assignedRole, reviewer) {
    const json = await this.redis.hget(REGISTRATIONS_REDIS_KEY, requestId);
    if (!json) throw new Error('注册请求不存在: ' + requestId);
    const req = JSON.parse(json);
    if (req.status !== 'PENDING') throw new Error('该请求已处理');
    req.status = 'APPROVED'; req.reviewedBy = reviewer; req.reviewedAt = Date.now();
    await this.redis.hset(REGISTRATIONS_REDIS_KEY, requestId, JSON.stringify(req));

    const userId = uuidv4().substring(0, 8);
    const user = {
      id: userId, username: req.username,
      passwordHash: req.passwordHash, salt: req.salt,
      role: assignedRole || req.requestedRole, approved: true,
      createdAt: Date.now(), approvedAt: Date.now(),
    };
    await this.redis.hset(USERS_REDIS_KEY, userId, JSON.stringify(user));
    console.log(`审批通过注册: ${req.username} → 角色: ${user.role}`);
    return user;
  }

  async rejectRegistration(requestId, reviewer) {
    const json = await this.redis.hget(REGISTRATIONS_REDIS_KEY, requestId);
    if (!json) throw new Error('注册请求不存在: ' + requestId);
    const req = JSON.parse(json);
    if (req.status !== 'PENDING') throw new Error('该请求已处理');
    req.status = 'REJECTED'; req.reviewedBy = reviewer; req.reviewedAt = Date.now();
    await this.redis.hset(REGISTRATIONS_REDIS_KEY, requestId, JSON.stringify(req));
    console.log(`拒绝注册: ${req.username}`);
  }

  // ==================== 地图管理 ====================

  async listMaps() {
    const all = await this.redis.hgetall(MAPS_REDIS_KEY);
    return Object.values(all).map(json => JSON.parse(json));
  }

  async getMap(mapId) {
    const json = await this.redis.hget(MAPS_REDIS_KEY, mapId);
    return json ? JSON.parse(json) : null;
  }

  async saveMap(name, data, createdBy) {
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
    await this.redis.hset(MAPS_REDIS_KEY, id, JSON.stringify(map));
    console.log(`保存地图: ${name} (${id}), 创建者: ${createdBy}`);
    return { ...map };
  }

  async updateMap(mapId, data) {
    const json = await this.redis.hget(MAPS_REDIS_KEY, mapId);
    if (!json) return false;
    const existing = JSON.parse(json);
    Object.assign(existing, data, { updatedAt: Date.now() });
    await this.redis.hset(MAPS_REDIS_KEY, mapId, JSON.stringify(existing));
    console.log(`更新地图: ${mapId}`);
    return true;
  }

  async deleteMap(mapId) {
    const json = await this.redis.hget(MAPS_REDIS_KEY, mapId);
    if (!json) return false;
    await this.redis.hdel(MAPS_REDIS_KEY, mapId);
    console.log(`删除地图: ${JSON.parse(json).name} (${mapId})`);
    return true;
  }

  // ==================== 仿真管理 ====================

  async listSimulations() {
    const all = await this.redis.hgetall(SIMULATIONS_REDIS_KEY);
    const maps = await this._loadMapsForLookup();
    return Object.values(all).map(json => {
      const sim = JSON.parse(json);
      const map = maps.get(sim.mapId);
      return { ...sim, mapName: map ? map.name : '未知地图' };
    });
  }

  async getSimulation(id) {
    const json = await this.redis.hget(SIMULATIONS_REDIS_KEY, id);
    return json ? JSON.parse(json) : null;
  }

  async createSimulation(name, mapId, createdBy) {
    const id = uuidv4().substring(0, 8);
    const sim = {
      id, name, mapId, sessionId: id,
      status: 'inactive', createdAt: Date.now(),
      createdBy, updatedAt: Date.now(),
    };
    await this.redis.hset(SIMULATIONS_REDIS_KEY, id, JSON.stringify(sim));
    console.log(`创建仿真: ${name} (${id}), 创建者: ${createdBy}`);
    return { ...sim };
  }

  async updateSimulationStatus(id, status) {
    const json = await this.redis.hget(SIMULATIONS_REDIS_KEY, id);
    if (!json) return false;
    const sim = JSON.parse(json);
    sim.status = status; sim.updatedAt = Date.now();
    await this.redis.hset(SIMULATIONS_REDIS_KEY, id, JSON.stringify(sim));
    return true;
  }

  async deleteSimulation(id) {
    const json = await this.redis.hget(SIMULATIONS_REDIS_KEY, id);
    if (!json) return false;
    await this.redis.hdel(SIMULATIONS_REDIS_KEY, id);
    console.log(`删除仿真: ${JSON.parse(json).name} (${id})`);
    return true;
  }

  // ==================== 内部工具 ====================

  async _loadMapsForLookup() {
    const all = await this.redis.hgetall(MAPS_REDIS_KEY);
    const map = new Map();
    for (const [id, json] of Object.entries(all)) {
      map.set(id, JSON.parse(json));
    }
    return map;
  }
}

module.exports = new JsonFileStore();
