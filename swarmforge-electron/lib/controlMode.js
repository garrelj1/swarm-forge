const EventEmitter = require('events');
const pty = require('node-pty');

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

class ControlModeClient extends EventEmitter {
  constructor({ socket, session }) {
    super();
    this._session = session;
    this._paneId = null;
    this._remainder = '';
    this._seenOpen = false;
    this._pendingCmds = []; // FIFO queue — tmux responds in command order

    const tmuxArgs = socket
      ? ['-S', socket, '-CC', 'attach', '-t', session]
      : ['-CC', 'attach', '-t', session];

    this._pty = pty.spawn('tmux', tmuxArgs, {
      name: 'xterm-256color',
      cols: 80,
      rows: 24,
    });

    this._pty.onData(raw => this._process(raw));
    this._pty.onExit(() => {
      this._pendingCmds.forEach(({ reject }) => reject(new Error('pty exited')));
      this._pendingCmds = [];
      this.emit('exit');
    });
  }

  _process(raw) {
    this._remainder += raw;

    // Strip DCS opening envelope once (may span chunks)
    if (!this._seenOpen) {
      const idx = this._remainder.indexOf('\x1bP1000p');
      if (idx !== -1) {
        this._remainder = this._remainder.slice(idx + 8);
        this._seenOpen = true;
        console.error(`[cm:${this._session}] DCS open found`);
      }
    }

    // Strip DCS closing envelope (appears once at stream end, 2 bytes, safe to strip globally)
    this._remainder = this._remainder.replace(/\x1b\\/g, '');

    const { lines, remainder } = parseLines(this._remainder);
    this._remainder = remainder;

    if (lines.length) console.error(`[cm:${this._session}] lines:`, lines);

    const blocks = parseCmdBlocks(lines);
    blocks.forEach(({ lines: bodyLines }) => {
      console.error(`[cm:${this._session}] cmd block (${bodyLines.length} lines), queue depth ${this._pendingCmds.length}`);
      const pending = this._pendingCmds.shift();
      if (pending) pending.resolve(bodyLines);
      else console.error(`[cm:${this._session}] WARNING: cmd block with no pending command`);
    });

    lines.forEach(line => {
      const out = parseOutputLine(line);
      if (out) this.emit('output', out.paneId, out.data);
    });
  }

  _send(cmd) {
    this._pty.write(cmd + '\n');
  }

  command(cmd) {
    return new Promise((resolve, reject) => {
      this._pendingCmds.push({ resolve, reject });
      this._send(cmd);
    });
  }

  resize(cols, rows) {
    this._pty.resize(cols, rows);
    if (this._paneId) this._send(`resize-pane -t ${this._paneId} -x ${cols} -y ${rows}`);
  }

  async init() {
    console.error(`[cm:${this._session}] init: sending list-panes`);
    const lines = await this.command(`list-panes -F "#{pane_id} #{window_id}"`);
    console.error(`[cm:${this._session}] init: list-panes returned`, lines);
    if (!lines.length) throw new Error('no panes found');
    this._paneId = lines[0].split(' ')[0];
    console.error(`[cm:${this._session}] init: paneId=${this._paneId}`);

    const screenLines = await this.command(`capture-pane -p -e -t ${this._paneId}`);
    if (screenLines.length) this.emit('output', this._paneId, screenLines.join('\r\n') + '\r\n');

    return this._paneId;
  }

  writeInput(data) {
    if (!this._paneId || !data) return;
    const hex = (Buffer.from(data, 'utf8').toString('hex').match(/.{2}/g) ?? []).join(' ');
    if (!hex) return;
    this._send(`send-keys -t ${this._paneId} -H ${hex}`);
  }

  kill() {
    try { this._pty.kill(); } catch {}
  }
}

module.exports = { parseLines, decodeOctal, parseOutputLine, parseCmdBlocks, ControlModeClient };
