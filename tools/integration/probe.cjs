/* Run against an isolated offline-mode test server on loopback, never a live world.
 * npm install in this directory, then: node probe.cjs 1.21.1 25611
 */
const mc = require('minecraft-protocol');
const bukkit = process.env.MCLOGIN_BUKKIT_TESTS === '1';
const assert = require('node:assert/strict');
const net = require('node:net');
const version = process.argv[2] || '1.21.1';
const port = Number(process.argv[3] || 25611);
const username = 'Port' + Date.now().toString(36);
const password = 'Test-password-42';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const checks = [];
let client, lastState;
const rconPort = Number(process.env.MCLOGIN_RCON_PORT);
const rconPassword = process.env.MCLOGIN_RCON_PASSWORD;
function rcon(command) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({host: '127.0.0.1', port: rconPort});
    let buffer = Buffer.alloc(0);
    const send = (id, type, text) => {
      const body = Buffer.from(text, 'utf8'), packet = Buffer.alloc(body.length + 14);
      packet.writeInt32LE(body.length + 10, 0); packet.writeInt32LE(id, 4); packet.writeInt32LE(type, 8);
      body.copy(packet, 12); socket.write(packet);
    };
    socket.setTimeout(5000, () => socket.destroy(new Error('RCON timeout')));
    socket.on('error', reject);
    socket.on('connect', () => send(1, 3, rconPassword));
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 4 && buffer.length >= buffer.readInt32LE(0) + 4) {
        const size = buffer.readInt32LE(0), packet = buffer.subarray(0, size + 4);
        buffer = buffer.subarray(size + 4);
        const id = packet.readInt32LE(4), type = packet.readInt32LE(8);
        if (id === -1) { socket.destroy(); reject(new Error('RCON authentication failed')); }
        else if (id === 1 && type === 2) send(2, 2, command);
        else if (id === 2) { socket.end(); resolve(packet.subarray(12, size + 2).toString('utf8')); }
      }
    });
  });
}
async function waitForRcon(command, expected, label, timeout = 10000) {
  const deadline = Date.now() + timeout; let response;
  do {
    response = await rcon(command);
    if (response.includes(expected)) {checks.push(label); return response;}
    await sleep(100);
  } while (Date.now() < deadline);
  throw new Error(label + ': ' + response);
}
async function position() {
  const result = await rcon('data get entity ' + username + ' Pos');
  const match = result.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
  assert(match, result); return match.slice(1).map(Number);
}
function move(c, p) {
  c.write('position', {x: p[0], y: p[1], z: p[2], onGround: true, flags: {onGround: true, hasHorizontalCollision: false}});
}

async function waitFor(predicate, label, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (predicate()) { checks.push(label); return; }
    await sleep(30);
  }
  throw new Error('Timed out: ' + label);
}
function connect() {
  const state = { messages: [], windows: [], stateId: 0, positions: [], disconnected: false };
  // minecraft-data's 1.20.6/1.21.1 recipe decoder cannot parse the vanilla
  // declare_recipes payload. Recipes are outside this authentication test;
  // retain their raw payload while decoding all tested menus/items normally.
  const customPackets = ['1.20.6', '1.21.1'].includes(version)
    ? { [require('minecraft-data')(version).version.majorVersion]: {play: {toClient: {types: {packet_declare_recipes: 'restBuffer'}}}} }
    : undefined;
  const c = mc.createClient({host: '127.0.0.1', port, username, auth: 'offline', version, customPackets});
  c.on('state', next => {
    const parser = c.deserializer;
    const parse = parser.parsePacketBuffer.bind(parser);
    parser.parsePacketBuffer = buffer => {
      try { return parse(buffer); }
      catch (error) {
        const mapping = require('minecraft-data')(version).protocol[next]?.toClient?.types?.packet?.[1]?.[0]?.type?.[1]?.mappings;
        let id = 0, shift = 0, index = 0, byte;
        do { byte = buffer[index++]; id |= (byte & 127) << shift; shift += 7; } while (byte & 128);
        console.error('Cannot decode packet:', next, mapping?.['0x' + id.toString(16).padStart(2, '0')] || id);
        throw error;
      }
    };
  });
  c.on('ping', packet => c.write('pong', {id: packet.id}));
  c.on('systemChat', packet => state.messages.push(JSON.stringify(packet.formattedMessage)));
  c.on('declare_commands', () => {state.commands = true;});
  c.on('open_window', packet => {state.windows.push(packet); state.window = packet;});
  c.on('window_items', packet => { if (packet.windowId === state.window?.windowId) state.stateId = packet.stateId; });
  c.on('set_slot', packet => { if (packet.windowId === state.window?.windowId) state.stateId = packet.stateId; });
  c.on('chunk_batch_finished', () => c.write('chunk_batch_received', {chunksPerTick: 10}));
  c.on('position', packet => {
    state.positions.push(packet);
    c.write('teleport_confirm', {teleportId: packet.teleportId});
    if (require('minecraft-data')(version).protocol.play.toServer.types.packet_player_loaded) c.write('player_loaded', {});
  });
  c.on('error', error => {state.error = error; console.error(error.message);});
  const disconnected = reason => {state.kick = JSON.stringify(reason); if (process.env.MCLOGIN_EXPECT_CONFLICT !== '1') console.error('Kicked:', state.kick);};
  c.on('kick_disconnect', disconnected);
  c.on('disconnect', disconnected);
  c.on('end', () => {state.disconnected = true;});
  client = c; lastState = state;
  return {c, state};
}
function click(c, state, slot) {
  const empty = ['1.21.8', '1.21.11'].includes(version) ? null : version === '1.20.4' ? {present: false} : {itemCount: 0};
  c.write('window_click', {windowId: state.window.windowId, stateId: state.stateId, slot, mouseButton: 0, mode: 0, changedSlots: [], cursorItem: empty});
}
async function main() {
  if (process.env.MCLOGIN_EXPECT_CONFLICT === '1') {
    const status = await rcon('mclogin status');
    assert(status.includes('incompatible authentication plugin AuthMe'), status);
    checks.push('loaded AuthMe latches authentication conflict');
    const {c,state} = connect();
    await waitFor(() => state.disconnected, 'conflicting authentication server rejects connection');
    assert(state.kick?.includes('incompatible authentication plugin AuthMe'), state.kick);
    checks.push('join rejection explicitly identifies conflicting plugin');
    assert.equal(state.windows.length, 0);
    checks.push('no authentication menu or playable session opened during conflict');
    c.end(); console.log(JSON.stringify({version, port, username, checks, passed:checks.length}, null, 2)); return;
  }
  let {c, state} = connect();
  await waitFor(() => state.windows.length, 'unauthenticated join opens login menu');
  if (rconPassword) {
    await sleep(700);
    const before = await position();
    move(c, [before[0] + 3, before[1], before[2]]);
    await sleep(600);
    const after = await position();
    assert(Math.abs(after[0] - before[0]) < 0.1, 'unauthenticated movement changed server position');
    checks.push('movement packets blocked before authentication');
    await rcon('give ' + username + ' minecraft:stone 1');
    const inventory = await rcon('data get entity ' + username + ' Inventory');
    c.write('block_dig', {status: 4, location: {x: 0, y: 0, z: 0}, face: 0, sequence: 0});
    await sleep(400);
    assert.equal(await rcon('data get entity ' + username + ' Inventory'), inventory);
    checks.push('item drop blocked before authentication');
    await rcon('clear ' + username);
    await rcon('execute at ' + username + ' run summon item ~ ~ ~ {Item:{id:"minecraft:diamond",Count:1b,count:1},PickupDelay:0s,NoGravity:1b}');
    await sleep(900);
    assert(!(await rcon('data get entity ' + username + ' Inventory')).includes('minecraft:diamond'));
    checks.push('item pickup blocked before authentication');
    await rcon('execute at ' + username + ' run kill @e[type=item,distance=..3]');
  }
  if (bukkit) {
    assert((await rcon('compatprobe status ' + username)).includes('auth=false registered=false'));
    checks.push('Bukkit LoginService reports unauthenticated unregistered player');
    assert((await rcon('papi parse ' + username + ' %mclogin_authenticated%')).includes('false'));
    checks.push('PlaceholderAPI reports false before authentication');
    await rcon('lp user ' + username + ' permission set mclogin.admin true');
    await waitForRcon('compatprobe status ' + username, 'admin=true', 'LuckPerms grants Bukkit administration permission');
    let index = state.messages.length;
    c.chat('/mclogin status');
    await waitFor(() => state.messages.slice(index).some(s => s.includes('请先登录')), 'administration permission never bypasses authentication');
    assert((await rcon('compatprobe teleport ' + username)).includes('teleport=false'));
    checks.push('foreign plugin teleport cancelled before authentication');
    assert((await rcon('compatprobe inventory ' + username)).includes('inventory=false'));
    checks.push('foreign plugin inventory cancelled before authentication');
    index = state.messages.length;
    c.chat('/essentials:home');
    await waitFor(() => state.messages.slice(index).some(s => s.includes('请先登录')), 'Essentials namespaced command blocked before authentication');
  }
  let count = state.windows.length;
  c.write('close_window', {windowId: state.window.windowId});
  await waitFor(() => state.windows.length > count, 'Escape reopens required authentication menu');
  const before = state.messages.length;
  c.chat('/help');
  await waitFor(() => state.messages.slice(before).some(s => s.includes('登录') || s.includes('log in')), 'unrelated command blocked before login');
  count = state.windows.length;
  click(c, state, 13);
  await waitFor(() => state.windows.length > count, 'register button opens anvil');
  c.write('name_item', {name: password});
  await sleep(150);
  count = state.windows.length;
  click(c, state, 2);
  await waitFor(() => state.windows.length > count, 'password submission opens confirmation anvil');
  c.write('name_item', {name: password});
  await sleep(150);
  click(c, state, 2);
  await waitFor(() => state.messages.some(s => s.includes('注册成功') || s.includes('Registration successful')), 'anvil registration completes asynchronous hashing');
  assert(!state.disconnected, state.kick || 'connection should remain open');
  checks.push('registration keeps client connected');
  if (bukkit) {
    const inventory = await rcon('data get entity ' + username + ' Inventory');
    assert(!inventory.includes('minecraft:paper') && !inventory.includes(password), inventory);
    checks.push('virtual anvil items and password text never enter player inventory');
  }
  c.end();
  await sleep(900);
  ({c, state} = connect());
  await waitFor(() => state.windows.length, 'offline reconnect requires authentication');
  await waitFor(() => state.positions.length && state.commands, 'reconnect receives world and command state');
  await sleep(400);
  let index = state.messages.length;
  c.chat((bukkit ? '/mclogin:login ' : '/login ') + 'Wrong-password-42');
  await waitFor(() => state.messages.slice(index).some(s => s.includes('密码错误') || s.includes('Incorrect password')), 'wrong password rejected');
  await sleep(700);
  index = state.messages.length;
  c.chat((bukkit ? '/mclogin:login ' : '/login ') + password);
  await waitFor(() => state.messages.slice(index).some(s => s.includes('登录成功') || s.includes('Login successful')), 'correct password authenticates after failure');
  index = state.messages.length;
  c.chat('/help');
  await waitFor(() => state.messages.length > index, 'commands available after authentication');
  assert(!state.messages.slice(index).some(s => s.includes('请先登录') || s.includes('must log in')));
  checks.push('login restriction released');
  if (rconPassword) {
    c.write('close_window', {windowId: state.window.windowId});
    await sleep(500);
    const before = await position();
    move(c, [before[0] + 0.4, before[1], before[2]]);
    await sleep(400);
    const after = await position();
    assert(Math.abs(after[0] - before[0] - 0.4) < 0.1, 'movement not released: ' + JSON.stringify({before, after, positions: state.positions.slice(-2)}));
    checks.push('movement released after authentication');
    await rcon('execute at ' + username + ' run summon item ~ ~ ~ {Item:{id:"minecraft:diamond",Count:1b,count:1},PickupDelay:0s,NoGravity:1b}');
    await sleep(900);
    assert((await rcon('data get entity ' + username + ' Inventory')).includes('minecraft:diamond'));
    checks.push('item pickup released after authentication');
  }
  if (bukkit) {
    assert((await rcon('compatprobe status ' + username)).includes('auth=true registered=true admin=true'));
    checks.push('Bukkit LoginService reports authenticated registered player');
    assert((await rcon('papi parse ' + username + ' %mclogin_authenticated%')).includes('true'));
    checks.push('PlaceholderAPI reports true after authentication');
    let index = state.messages.length;
    c.chat('/mclogin status');
    await waitFor(() => state.messages.slice(index).some(s => s.includes('storageHealthy=true')), 'LuckPerms permission enables authenticated administration');
    await rcon('lp user ' + username + ' permission unset mclogin.admin');
    await waitForRcon('compatprobe status ' + username, 'admin=false', 'LuckPerms revokes Bukkit administration permission');
    index = state.messages.length;
    c.chat('/mclogin status');
    await waitFor(() => state.messages.slice(index).some(s => s.includes('缺少权限')), 'LuckPerms permission revocation takes effect');
    assert((await rcon('compatprobe inventory ' + username)).includes('inventory=true'));
    checks.push('foreign plugin inventory released after authentication');
    c.write('close_window', {windowId: state.window.windowId});
    assert((await rcon('compatprobe teleport ' + username)).includes('teleport=true'));
    checks.push('foreign plugin teleport released after authentication');
    const vault = await rcon('vault-info');
    assert(vault.includes('LuckPerms') && vault.includes('Essentials'), vault);
    checks.push('Vault resolves LuckPerms permissions and Essentials economy');
    const worldedit = await rcon('worldedit version');
    assert(worldedit.includes('WorldEdit'), worldedit);
    checks.push('WorldEdit command remains available with authentication plugin');
    await rcon('rg flag -w world __global__ block-break deny');
    assert((await rcon('compatprobe break ' + username)).includes('cancelled=true'));
    checks.push('WorldGuard still cancels protected block events after authentication');
    await rcon('rg flag -w world __global__ block-break');
    assert((await rcon('compatprobe break ' + username)).includes('cancelled=false'));
    checks.push('unprotected block events remain available after authentication');
    assert((await rcon('compatprobe status ' + username)).includes('AUTHENTICATED:LOGIN'));
    checks.push('synchronous authentication events delivered to another plugin');

    await rcon('compatprobe verifier');
    let previous = state.windows.length;
    c.chat('/mclogin logout');
    await waitFor(() => state.windows.length > previous, 'logout immediately requires authentication again');
    assert((await rcon('papi parse ' + username + ' %mclogin_authenticated%')).includes('false'));
    checks.push('PlaceholderAPI clears authentication on logout');
    index = state.messages.length;
    c.chat('/mclogin reset');
    await waitFor(() => state.messages.slice(index).some(s => s.includes('外部核验')), 'reset waits for external verifier');
    await rcon('compatprobe deny ' + username);
    await waitFor(() => state.messages.slice(index).some(s => s.includes('身份核验失败')), 'negative external verification cannot authorize reset');
    c.chat('/mclogin reset');
    await sleep(300);
    previous = state.windows.length;
    await rcon('compatprobe approve ' + username);
    await waitFor(() => state.windows.length > previous, 'positive external verification opens reset anvil');
    const resetPassword = 'Reset-password-84';
    c.write('name_item', {name: resetPassword});await sleep(200);
    previous = state.windows.length;click(c,state,2);
    await waitFor(() => state.windows.length > previous, 'reset requires password confirmation');
    c.write('name_item', {name: resetPassword});await sleep(200);
    index = state.messages.length;click(c,state,2);
    await waitFor(() => state.messages.slice(index).some(s => s.includes('密码已重置')), 'external reset commits asynchronous hash');
    assert((await rcon('compatprobe status ' + username)).includes('auth=false'));
    checks.push('password reset does not automatically authenticate');
    await sleep(700);index = state.messages.length;
    c.chat('/mclogin login ' + password);
    await waitFor(() => state.messages.slice(index).some(s => s.includes('密码错误')), 'old password rejected after reset');
    await sleep(700);index = state.messages.length;
    c.chat('/mclogin login ' + resetPassword);
    await waitFor(() => state.messages.slice(index).some(s => s.includes('登录成功')), 'new password authenticates after reset');
    previous = state.windows.length;c.chat('/mclogin logout');
    await waitFor(() => state.windows.length > previous, 'logout after reset reopens menu');
    c.chat('/mclogin reset');await sleep(300);previous = state.windows.length;
    await rcon('compatprobe approve ' + username);
    await waitFor(() => state.windows.length > previous, 'second external grant opens reset anvil');
    await rcon('compatprobe withdraw');
    c.write('name_item', {name: 'Revoked-password-90'});await sleep(200);
    previous = state.windows.length;click(c,state,2);
    await waitFor(() => state.windows.length > previous, 'revoked reset reaches confirmation without granting write');
    c.write('name_item', {name: 'Revoked-password-90'});await sleep(200);index=state.messages.length;click(c,state,2);
    await waitFor(() => state.messages.slice(index).some(s => s.includes('找回授权无效')), 'provider removal revokes already approved reset grant');
    await sleep(700);index=state.messages.length;c.chat('/mclogin login ' + resetPassword);
    await waitFor(() => state.messages.slice(index).some(s => s.includes('登录成功')), 'revoked reset leaves existing password intact');
    await sleep(700);index=state.messages.length;
    c.chat('/mclogin changepassword ' + resetPassword + ' Final-password-95');
    await waitFor(() => state.messages.slice(index).some(s => s.includes('密码已修改')), 'authenticated password change completes asynchronously');
    await sleep(700);index=state.messages.length;c.chat('/mclogin unregister Final-password-95');
    await waitFor(() => state.messages.slice(index).some(s => s.includes('账号已注销')), 'password-verified unregister removes account');
    assert((await rcon('compatprobe status ' + username)).includes('auth=false registered=false'));
    checks.push('unregister revokes authentication and registration in Bukkit API');
  }
  assert(!state.error, String(state.error));
  c.end();
  console.log(JSON.stringify({version, port, username, checks, passed: checks.length}, null, 2));
}
main().catch(error => {console.error(error.stack); console.error(JSON.stringify({checks, messages: lastState?.messages.slice(-8)}, null, 2)); client?.end(); process.exitCode = 1;});
