const EventEmitter = require('events');

function parseLines(raw) {
  const parts = raw.split(/\r?\n/);
  const remainder = parts.pop();
  const lines = parts.filter(l => l.length > 0);
  return { lines, remainder };
}

module.exports = { parseLines };
