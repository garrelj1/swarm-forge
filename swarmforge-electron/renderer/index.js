const { computeGrid } = require('../lib/grid');

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

  if (columnEls.length > 1) {
    Split(columnEls, {
      direction: 'horizontal',
      sizes: columnEls.map(() => 100 / cols),
      gutterSize: 4,
    });
  }

  columnEls.forEach(colEl => {
    const panels = Array.from(colEl.querySelectorAll('.panel'));
    if (panels.length > 1) {
      Split(panels, {
        direction: 'vertical',
        sizes: panels.map(() => 100 / panels.length),
        gutterSize: 4,
      });
    }
  });

  return termDivs;
}

// Temporary: render mock sessions so the layout is visible without a live swarm
const mockSessions = [
  { role: 'specifier' },
  { role: 'coder' },
  { role: 'refactorer' },
  { role: 'architect' },
];
buildLayout(mockSessions);
