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

function parseCmdBlocks(lines) {
  const results = [];
  let current = null;
  for (const line of lines) {
    const begin = line.match(/^%begin \d+ (\d+) \d+/);
    const end = line.match(/^%end \d+ (\d+) \d+/);
    if (begin) { current = { seq: parseInt(begin[1]), lines: [] }; }
    else if (end && current) { results.push(current); current = null; }
    else if (current) { current.lines.push(line); }
  }
  return results;
}

module.exports = { parseLines, decodeOctal, parseOutputLine, parseCmdBlocks };
