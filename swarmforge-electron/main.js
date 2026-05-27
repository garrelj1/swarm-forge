const { app, BrowserWindow, ipcMain } = require('electron');
const { execFile } = require('child_process');
const path = require('path');
const fs = require('fs');
const pty = require('node-pty');
const { parseSessions } = require('./lib/sessions');

let workingDir = process.cwd();
let mainWindow = null;
const ptys = new Map(); // role → pty instance

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

    sessions.forEach(({ role, session }) => {
      try {
        const p = pty.spawn('tmux', ['attach-session', '-t', session], {
          name: 'xterm-256color',
          cols: 80,
          rows: 24,
          cwd: workingDir,
        });

        p.onData(data => send('pty:data', { role, data }));
        ptys.set(role, p);
      } catch (err) {
        send('pty:data', { role, data: `\x1b[31m[error] failed to attach to session "${session}": ${err.message}\x1b[0m\r\n` });
      }
    });
  });
}

ipcMain.on('pty:write', (_, { role, data }) => {
  ptys.get(role)?.write(data);
});

ipcMain.on('pty:resize', (_, { role, cols, rows }) => {
  ptys.get(role)?.resize(cols, rows);
});

ipcMain.on('swarm:cleanup', () => {
  const socketFile = path.join(workingDir, '.swarmforge', 'tmux-socket');
  const tmuxSocket = fs.readFileSync(socketFile, 'utf8').trim();
  const windowIds = path.join(workingDir, '.swarmforge', 'window-ids');
  const cleanupScript = path.join(__dirname, '..', 'swarm-cleanup.sh');
  execFile(cleanupScript, [tmuxSocket, windowIds, ...ptys.keys()], err => {
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
  ptys.forEach(p => p.kill());
  app.quit();
});
