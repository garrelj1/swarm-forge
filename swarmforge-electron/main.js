const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

let workingDir = process.cwd();

function createWindow() {
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
  if (wdIdx !== -1) workingDir = args[wdIdx + 1];
  createWindow();
});

app.on('window-all-closed', () => app.quit());
