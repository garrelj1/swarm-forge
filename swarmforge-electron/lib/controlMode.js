const EventEmitter = require('events');

function parseLines(raw) {
  const parts = raw.split(/\r?\n/);
  const remainder = parts.pop();
  const lines = parts.filter(l => l.length > 0);
  return { lines, remainder };
}

function decodeOctal(s) {
  return s.replace(/\\([0-7]{3})/g, (_, o) => String.fromCharCode(parseInt(o, 8)));
}

function parseOutputLine(line) {
  const m = line.match(/^%output (%\S+) (.*)/s);
  if (!m) return null;
  return { paneId: m[1], data: decodeOctal(m[2]) };
}

module.exports = { parseLines, decodeOctal, parseOutputLine };
