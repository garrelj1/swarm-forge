const { app, BrowserWindow, ipcMain } = require('electron');
const { execFile } = require('child_process');
const path = require('path');
const fs = require('fs');
const { parseSessions } = require('./lib/sessions');
const { ControlModeClient } = require('./lib/controlMode');

let workingDir = process.cwd();
let mainWindow = null;
const clients = new Map(); // role → ControlModeClient

function send(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload);
  }
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: false,
      nodeIntegration: true,
    },
    title: 'SwarmForge',
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  mainWindow.webContents.on('did-finish-load', () => {
    const tsvPath = path.join(workingDir, '.swarmforge', 'sessions.tsv');
    const sessions = parseSessions(tsvPath);

    send('swarm:sessions', sessions);

    const socketFile = path.join(workingDir, '.swarmforge', 'tmux-socket');
    let tmuxSocket = null;
    try { tmuxSocket = fs.readFileSync(socketFile, 'utf8').trim(); } catch {}

    sessions.forEach(({ role, session }) => {
      const client = new ControlModeClient({ socket: tmuxSocket, session });

      client.on('output', (paneId, data) => send('pty:data', { role, data }));
      client.on('exit', () => send('pty:data', { role, data: '\r\n\x1b[33m[session ended]\x1b[0m\r\n' }));

      client.init().catch(err => {
        send('pty:data', { role, data: `\x1b[31m[error] ${err.message}\x1b[0m\r\n` });
      });

      clients.set(role, client);
    });
  });
}

ipcMain.on('pty:write', (_, { role, data }) => {
  clients.get(role)?.writeInput(data);
});

ipcMain.on('pty:resize', (_, { role, cols, rows }) => {
  clients.get(role)?.resize(cols, rows);
});

ipcMain.on('swarm:cleanup', () => {
  const socketFile = path.join(workingDir, '.swarmforge', 'tmux-socket');
  const tmuxSocket = fs.readFileSync(socketFile, 'utf8').trim();
  const windowIds = path.join(workingDir, '.swarmforge', 'window-ids');
  const cleanupScript = path.join(__dirname, '..', 'swarm-cleanup.sh');
  execFile(cleanupScript, [tmuxSocket, windowIds, ...clients.keys()], err => {
    if (err) console.error('swarm-cleanup.sh failed:', err.message);
  });
});

app.whenReady().then(() => {
  const args = process.argv.slice(2);
  const wdIdx = args.indexOf('--working-dir');
  if (wdIdx !== -1 && args[wdIdx + 1]) workingDir = args[wdIdx + 1];
  createWindow();
});

app.on('window-all-closed', () => {
  clients.forEach(c => c.kill());
  app.quit();
});
