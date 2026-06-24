// JSON 文件持久化 — 与 Java JsonFileStore 完全一致
const fs = require('fs');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const passwordUtil = require('../auth/password');

const DATA_DIR = path.join(__dirname, '..', 'data');
const USERS_FILE = path.join(DATA_DIR, 'users.json');
const REGISTRATIONS_FILE = path.join(DATA_DIR, 'registrations.json');
const MAPS_FILE = path.join(DATA_DIR, 'maps.json');
const SIMULATIONS_FILE = path.join(DATA_DIR, 'simulations.json');

class JsonFileStore {
  constructor() {
    this.users = new Map();
    this.registrations = new Map();
    this.savedMaps = new Map();
    this.simulations = new Map();

    if (!fs.existsSync(DATA_DIR)) {
      fs.mkdirSync(DATA_DIR, { recursive: true });
    }

    this._loadUsers();
    this._loadRegistrations();
    this._loadMaps();
    this._loadSimulations();

    if (this.users.size === 0) {
      this._createDefaultAdmin();
    }

    console.log(
      `JsonFileStore 初始化完成：${this.users.size} 个用户，${this.registrations.size} 条注册请求，${this.savedMaps.size} 张地图，${this.simulations.size} 个仿真`
    );
  }

  // ==================== 用户管理 ====================

  findByUsername(username) {
    for (const u of this.users.values()) {
      if (u.username === username) return u;
    }
    return null;
  }

  findById(id) {
    return this.users.get(id) || null;
  }

  listAllUsers() {
    return Array.from(this.users.values());
  }

  createUser(username, plainPassword, role, approved) {
    if (this.findByUsername(username)) {
      throw new Error('用户名已存在: ' + username);
    }
    const id = uuidv4().substring(0, 8);
    const salt = passwordUtil.generateSalt();
    const passwordHash = passwordUtil.hash(plainPassword, salt);
    const user = {
      id,
      username,
      passwordHash,
      salt,
      role,
      approved,
      createdAt: Date.now(),
      approvedAt: approved ? Date.now() : null,
    };
    this.users.set(id, user);
    this._saveUsers();
    console.log(`创建用户: ${username} (角色: ${role}, 审批: ${approved})`);
    return user;
  }

  resetPassword(userId, newPassword) {
    const user = this.users.get(userId);
    if (!user) return false;
    user.salt = passwordUtil.generateSalt();
    user.passwordHash = passwordUtil.hash(newPassword, user.salt);
    this._saveUsers();
    console.log(`重置密码: ${user.username}`);
    return true;
  }

  deleteUser(userId) {
    const removed = this.users.get(userId);
    if (!removed) return false;
    this.users.delete(userId);
    this._saveUsers();
    console.log(`删除用户: ${removed.username}`);
    return true;
  }

  // ==================== 注册请求管理 ====================

  createRegistration(username, plainPassword, requestedRole) {
    for (const r of this.registrations.values()) {
      if (r.username === username && r.status === 'PENDING') {
        throw new Error('已有待审批的注册请求: ' + username);
      }
    }
    if (this.findByUsername(username)) {
      throw new Error('用户名已存在: ' + username);
    }
    const id = uuidv4().substring(0, 8);
    const salt = passwordUtil.generateSalt();
    const passwordHash = passwordUtil.hash(plainPassword, salt);
    const req = {
      id,
      username,
      passwordHash,
      salt,
      requestedRole,
      status: 'PENDING',
      createdAt: Date.now(),
      reviewedBy: null,
      reviewedAt: null,
    };
    this.registrations.set(id, req);
    this._saveRegistrations();
    console.log(`创建注册请求: ${username} (申请角色: ${requestedRole})`);
    return req;
  }

  listRegistrations() {
    return Array.from(this.registrations.values());
  }

  approveRegistration(requestId, assignedRole, reviewer) {
    const req = this.registrations.get(requestId);
    if (!req) throw new Error('注册请求不存在: ' + requestId);
    if (req.status !== 'PENDING') throw new Error('该请求已处理');

    req.status = 'APPROVED';
    req.reviewedBy = reviewer;
    req.reviewedAt = Date.now();
    this._saveRegistrations();

    const userId = uuidv4().substring(0, 8);
    const user = {
      id: userId,
      username: req.username,
      passwordHash: req.passwordHash,
      salt: req.salt,
      role: assignedRole || req.requestedRole,
      approved: true,
      createdAt: Date.now(),
      approvedAt: Date.now(),
    };
    this.users.set(userId, user);
    this._saveUsers();

    console.log(`审批通过注册: ${req.username} → 角色: ${user.role}`);
    return user;
  }

  rejectRegistration(requestId, reviewer) {
    const req = this.registrations.get(requestId);
    if (!req) throw new Error('注册请求不存在: ' + requestId);
    if (req.status !== 'PENDING') throw new Error('该请求已处理');

    req.status = 'REJECTED';
    req.reviewedBy = reviewer;
    req.reviewedAt = Date.now();
    this._saveRegistrations();
    console.log(`拒绝注册: ${req.username}`);
  }

  // ==================== 地图管理 ====================

  listMaps() {
    return Array.from(this.savedMaps.values());
  }

  getMap(mapId) {
    const map = this.savedMaps.get(mapId);
    return map ? { ...map } : null;
  }

  saveMap(name, data, createdBy) {
    const id = uuidv4().substring(0, 8);
    const map = {
      id,
      name,
      mapWidth: data.mapWidth || 40,
      mapHeight: data.mapHeight || 30,
      carCount: data.carCount || 5,
      obstacleDensity: data.obstacleDensity || 0.2,
      algorithm: data.algorithm || 'A_STAR',
      obstacles: data.obstacles || [],
      carPositions: data.carPositions || [],
      createdAt: Date.now(),
      createdBy,
      ...data,
      id, // 确保不被覆盖
    };
    this.savedMaps.set(id, map);
    this._persistMaps();
    console.log(`保存地图: ${name} (${id}), 创建者: ${createdBy}`);
    return { ...map };
  }

  updateMap(mapId, data) {
    const existing = this.savedMaps.get(mapId);
    if (!existing) return false;
    Object.assign(existing, data, { updatedAt: Date.now() });
    this._persistMaps();
    console.log(`更新地图: ${mapId}`);
    return true;
  }

  deleteMap(mapId) {
    const removed = this.savedMaps.get(mapId);
    if (!removed) return false;
    this.savedMaps.delete(mapId);
    this._persistMaps();
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
      id,
      name,
      mapId,
      sessionId: id,
      status: 'inactive',
      createdAt: Date.now(),
      createdBy,
    };
    this.simulations.set(id, sim);
    this._persistSimulations();
    console.log(`创建仿真: ${name} (${id}), 创建者: ${createdBy}`);
    return { ...sim };
  }

  updateSimulationStatus(id, status) {
    const sim = this.simulations.get(id);
    if (!sim) return false;
    sim.status = status;
    this._persistSimulations();
    return true;
  }

  deleteSimulation(id) {
    const removed = this.simulations.get(id);
    if (!removed) return false;
    this.simulations.delete(id);
    this._persistSimulations();
    console.log(`删除仿真: ${removed.name} (${id})`);
    return true;
  }

  // ==================== 内部方法 ====================

  _createDefaultAdmin() {
    const salt = passwordUtil.generateSalt();
    const hash = passwordUtil.hash('admin123', salt);
    const admin = {
      id: 'admin001',
      username: 'admin',
      passwordHash: hash,
      salt,
      role: 'ADMIN',
      approved: true,
      createdAt: Date.now(),
      approvedAt: Date.now(),
    };
    this.users.set(admin.id, admin);
    this._saveUsers();
    console.log('创建默认管理员: admin / admin123');
  }

  _loadUsers() {
    try {
      if (fs.existsSync(USERS_FILE)) {
        const list = JSON.parse(fs.readFileSync(USERS_FILE, 'utf8'));
        for (const u of list) {
          this.users.set(u.id, u);
        }
      }
    } catch (e) {
      console.error('加载用户文件失败', e.message);
    }
  }

  _saveUsers() {
    try {
      const list = Array.from(this.users.values());
      fs.writeFileSync(USERS_FILE, JSON.stringify(list, null, 2), 'utf8');
    } catch (e) {
      console.error('保存用户文件失败', e.message);
    }
  }

  _loadRegistrations() {
    try {
      if (fs.existsSync(REGISTRATIONS_FILE)) {
        const list = JSON.parse(fs.readFileSync(REGISTRATIONS_FILE, 'utf8'));
        for (const r of list) {
          this.registrations.set(r.id, r);
        }
      }
    } catch (e) {
      console.error('加载注册请求文件失败', e.message);
    }
  }

  _saveRegistrations() {
    try {
      const list = Array.from(this.registrations.values());
      fs.writeFileSync(REGISTRATIONS_FILE, JSON.stringify(list, null, 2), 'utf8');
    } catch (e) {
      console.error('保存注册请求文件失败', e.message);
    }
  }

  _loadMaps() {
    try {
      if (fs.existsSync(MAPS_FILE)) {
        const list = JSON.parse(fs.readFileSync(MAPS_FILE, 'utf8'));
        for (const m of list) {
          if (m.id) this.savedMaps.set(m.id, m);
        }
      }
    } catch (e) {
      console.error('加载地图文件失败', e.message);
    }
  }

  _persistMaps() {
    try {
      const list = Array.from(this.savedMaps.values());
      fs.writeFileSync(MAPS_FILE, JSON.stringify(list, null, 2), 'utf8');
    } catch (e) {
      console.error('保存地图文件失败', e.message);
    }
  }

  _loadSimulations() {
    try {
      if (fs.existsSync(SIMULATIONS_FILE)) {
        const list = JSON.parse(fs.readFileSync(SIMULATIONS_FILE, 'utf8'));
        for (const s of list) {
          if (s.id) this.simulations.set(s.id, s);
        }
      }
    } catch (e) {
      console.error('加载仿真文件失败', e.message);
    }
  }

  _persistSimulations() {
    try {
      const list = Array.from(this.simulations.values());
      fs.writeFileSync(SIMULATIONS_FILE, JSON.stringify(list, null, 2), 'utf8');
    } catch (e) {
      console.error('保存仿真文件失败', e.message);
    }
  }
}

module.exports = new JsonFileStore();
