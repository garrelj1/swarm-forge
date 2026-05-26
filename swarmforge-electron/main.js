const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

function createWindow(workingDir) {
  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
    },
    title: 'SwarmForge',
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

app.whenReady().then(() => {
  const args = process.argv.slice(2);
  const wdIdx = args.indexOf('--working-dir');
  const workingDir = wdIdx !== -1 ? args[wdIdx + 1] : process.cwd();
  createWindow(workingDir);
});

app.on('window-all-closed', () => app.quit());
