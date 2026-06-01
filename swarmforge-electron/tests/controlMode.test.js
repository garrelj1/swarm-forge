const { parseLines } = require('../lib/controlMode');

describe('parseLines', () => {
  test('returns complete lines split by CRLF', () => {
    const { lines } = parseLines('%begin 1 2 1\r\nhello\r\n%end 1 2 1\r\n');
    expect(lines).toEqual(['%begin 1 2 1', 'hello', '%end 1 2 1']);
  });

  test('returns complete lines split by LF only', () => {
    const { lines } = parseLines('%begin 1 2 1\nhello\n');
    expect(lines).toEqual(['%begin 1 2 1', 'hello']);
  });

  test('buffers incomplete lines and returns remainder', () => {
    const { lines, remainder } = parseLines('%begin 1 2 1\r\n%out');
    expect(lines).toEqual(['%begin 1 2 1']);
    expect(remainder).toBe('%out');
  });

  test('accumulates across calls using remainder', () => {
    const r1 = parseLines('%beg');
    const r2 = parseLines(r1.remainder + 'in 1 2 1\r\n');
    expect(r2.lines).toEqual(['%begin 1 2 1']);
  });

  test('returns empty lines and empty remainder for empty input', () => {
    const { lines, remainder } = parseLines('');
    expect(lines).toEqual([]);
    expect(remainder).toBe('');
  });

  test('filters out empty lines', () => {
    const { lines } = parseLines('a\r\n\r\nb\r\n');
    expect(lines).toEqual(['a', 'b']);
  });
});

const { decodeOctal, parseOutputLine } = require('../lib/controlMode');

describe('decodeOctal', () => {
  test('decodes octal escape sequences', () => {
    expect(decodeOctal('hi\\012there')).toBe('hi\nthere');
  });

  test('leaves non-octal text unchanged', () => {
    expect(decodeOctal('hello world')).toBe('hello world');
  });

  test('decodes multiple escapes', () => {
    expect(decodeOctal('\\150\\151')).toBe('hi');
  });
});

describe('parseOutputLine', () => {
  test('parses pane id and decoded data', () => {
    expect(parseOutputLine('%output %0 hi\\012')).toEqual({ paneId: '%0', data: 'hi\n' });
  });

  test('returns null for non-output lines', () => {
    expect(parseOutputLine('%begin 1 2 1')).toBeNull();
  });
});

const { parseCmdBlocks } = require('../lib/controlMode');

describe('parseCmdBlocks', () => {
  test('returns body lines for a complete block', () => {
    const lines = ['%begin 1748 42 1', '%0 @0', '%end 1748 42 1'];
    expect(parseCmdBlocks(lines)).toEqual([{ seq: 42, lines: ['%0 @0'] }]);
  });

  test('returns empty array for no complete blocks', () => {
    expect(parseCmdBlocks(['%begin 1748 42 1', 'partial'])).toEqual([]);
  });

  test('handles block with no body lines', () => {
    const lines = ['%begin 1748 42 1', '%end 1748 42 1'];
    expect(parseCmdBlocks(lines)).toEqual([{ seq: 42, lines: [] }]);
  });

  test('handles multiple blocks', () => {
    const lines = [
      '%begin 1748 1 1', 'a', '%end 1748 1 1',
      '%begin 1748 2 1', 'b', '%end 1748 2 1',
    ];
    expect(parseCmdBlocks(lines)).toEqual([
      { seq: 1, lines: ['a'] },
      { seq: 2, lines: ['b'] },
    ]);
  });
});

const { stripAnsi, isWaitingInput } = require('../lib/controlMode');

describe('stripAnsi', () => {
  test('removes SGR sequences', () => {
    expect(stripAnsi('\x1b[32mhello\x1b[0m')).toBe('hello');
  });

  test('leaves plain text unchanged', () => {
    expect(stripAnsi('hello world')).toBe('hello world');
  });
});

describe('isWaitingInput', () => {
  test('detects tool permission prompt', () => {
    expect(isWaitingInput('  ❯ Yes\n  No\n')).toBe(true);
  });

  test('detects unsafe command prompt', () => {
    expect(isWaitingInput('Do you want to proceed?\n1. Yes\n2. No\n')).toBe(true);
  });

  test('detects prompt with ANSI sequences around it', () => {
    expect(isWaitingInput('\x1b[32m❯\x1b[0m Yes')).toBe(true);
  });

  test('returns false for normal output', () => {
    expect(isWaitingInput('Running tests...\n> jest\n')).toBe(false);
  });

  test('returns false for empty string', () => {
    expect(isWaitingInput('')).toBe(false);
  });
});
