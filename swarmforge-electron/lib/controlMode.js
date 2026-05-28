const EventEmitter = require('events');

function parseLines(raw) {
  const stripped = raw
    .replace(/\x1bP1000p/, '')
    .replace(/\x1b\\/g, '');
  const parts = stripped.split(/\r?\n/);
  const remainder = parts.pop();
  const lines = parts.filter(l => l.length > 0);
  return { lines, remainder };
}

module.exports = { parseLines };
