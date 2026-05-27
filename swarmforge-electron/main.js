const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const pty = require('node-pty');
const { parseSessions } = require('./lib/sessions');

let workingDir = process.cwd();
let mainWindow = null;
const ptys = new Map(); // role → pty instance

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

    mainWindow.webContents.send('swarm:sessions', sessions);

    sessions.forEach(({ role, session }) => {
      const p = pty.spawn('tmux', ['attach-session', '-t', session], {
        name: 'xterm-256color',
        cols: 80,
        rows: 24,
        cwd: workingDir,
      });

      p.onData(data => {
        if (mainWindow) mainWindow.webContents.send('pty:data', { role, data });
      });

      ptys.set(role, p);
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
  const { execFile } = require('child_process');
  const cleanupScript = path.join(__dirname, '..', 'swarm-cleanup.sh');
  const windowIds = path.join(workingDir, '.swarmforge', 'window-ids');
  execFile(cleanupScript, [windowIds, ...ptys.keys()], () => {});
});

app.whenReady().then(() => {
  const args = process.argv.slice(2);
  const wdIdx = args.indexOf('--working-dir');
  if (wdIdx !== -1) workingDir = args[wdIdx + 1];
  createWindow();
});

app.on('window-all-closed', () => {
  ptys.forEach(p => p.kill());
  app.quit();
});
