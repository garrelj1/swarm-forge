const { ipcRenderer } = require('electron');

// contextIsolation is false — expose IPC helpers directly on window.swarm
window.swarm = {
  onSessions: (cb) => ipcRenderer.once('swarm:sessions', (_, sessions) => cb(sessions)),
  onPtyData: (cb) => ipcRenderer.on('pty:data', (_, payload) => cb(payload)),
  writePty: (role, data) => ipcRenderer.send('pty:write', { role, data }),
  resizePty: (role, cols, rows) => ipcRenderer.send('pty:resize', { role, cols, rows }),
  cleanup: () => ipcRenderer.send('swarm:cleanup'),
  onWaitingInput: (cb) => ipcRenderer.on('pty:waiting-input', (_, payload) => cb(payload)),
  onResumed: (cb) => ipcRenderer.on('pty:resumed', (_, payload) => cb(payload)),
};
