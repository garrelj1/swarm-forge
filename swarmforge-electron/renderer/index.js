const { computeGrid } = require('../lib/grid');
const { Terminal } = require('xterm');
const { FitAddon } = require('@xterm/addon-fit');
const { WebLinksAddon } = require('@xterm/addon-web-links');

function buildLayout(sessions) {
  const n = sessions.length;
  const container = document.getElementById('container');
  container.innerHTML = '';

  if (n === 0) {
    container.innerHTML = '<div style="padding:20px;color:#555">No active sessions found.</div>';
    return [];
  }

  const { cols } = computeGrid(n);
  const columnEls = [];
  const termDivs = [];

  for (let c = 0; c < cols; c++) {
    const colEl = document.createElement('div');
    colEl.className = 'column';
    colEl.style.flex = '1';
    container.appendChild(colEl);
    columnEls.push(colEl);
  }

  sessions.forEach((s, i) => {
    const colEl = columnEls[i % cols];

    const panel = document.createElement('div');
    panel.className = 'panel';
    panel.style.flex = '1';

    const label = document.createElement('div');
    label.className = 'panel-label';
    label.textContent = s.role;

    const termDiv = document.createElement('div');
    termDiv.className = 'panel-term';
    termDiv.id = `term-${s.role}`;

    panel.appendChild(label);
    panel.appendChild(termDiv);
    colEl.appendChild(panel);
    termDivs.push({ role: s.role, el: termDiv });
  });

  const refitAll = () => terminals.forEach(({ fitAddon }) => fitAddon.fit());

  if (columnEls.length > 1) {
    Split(columnEls, {
      direction: 'horizontal',
      sizes: columnEls.map(() => 100 / cols),
      gutterSize: 4,
      onDragEnd: refitAll,
    });
  }

  columnEls.forEach(colEl => {
    const panels = Array.from(colEl.querySelectorAll('.panel'));
    if (panels.length > 1) {
      Split(panels, {
        direction: 'vertical',
        sizes: panels.map(() => 100 / panels.length),
        gutterSize: 4,
        onDragEnd: refitAll,
      });
    }
  });

  return termDivs;
}

const terminals = new Map(); // role → { term, fitAddon }

function attachTerminals(termDivs) {
  termDivs.forEach(({ role, el }) => {
    const term = new Terminal({
      theme: { background: '#1e1e1e', foreground: '#cccccc', cursor: '#cccccc' },
      fontFamily: 'Menlo, Monaco, "Courier New", monospace',
      fontSize: 13,
      cursorBlink: true,
    });
    const fitAddon = new FitAddon();
    const webLinksAddon = new WebLinksAddon();
    term.loadAddon(fitAddon);
    term.loadAddon(webLinksAddon);
    term.open(el);
    requestAnimationFrame(() => fitAddon.fit());
    terminals.set(role, { term, fitAddon });

    term.write(`\x1b[32m[${role}]\x1b[0m connecting...\r\n`);
  });
}

window.addEventListener('resize', () => {
  terminals.forEach(({ fitAddon }) => fitAddon.fit());
});

// Temporary mock — replaced by IPC in Task 7
const mockSessions = [
  { role: 'specifier' },
  { role: 'coder' },
  { role: 'refactorer' },
  { role: 'architect' },
];
const termDivs = buildLayout(mockSessions);
attachTerminals(termDivs);
