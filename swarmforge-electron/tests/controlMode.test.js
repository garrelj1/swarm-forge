const { parseLines } = require('../lib/controlMode');

describe('parseLines', () => {
  test('strips DCS opening envelope', () => {
    const { lines } = parseLines('\x1bP1000p%session-changed $0 probe\r\n');
    expect(lines).toEqual(['%session-changed $0 probe']);
  });

  test('strips DCS closing envelope', () => {
    const { lines } = parseLines('%exit\r\n\x1b\\');
    expect(lines).toEqual(['%exit']);
  });

  test('buffers incomplete lines and returns remainder', () => {
    const { lines, remainder } = parseLines('%begin 1 2 1\r\n%out');
    expect(lines).toEqual(['%begin 1 2 1']);
    expect(remainder).toBe('%out');
  });

  test('returns multiple complete lines', () => {
    const { lines } = parseLines('%begin 1 2 1\r\nhello\r\n%end 1 2 1\r\n');
    expect(lines).toEqual(['%begin 1 2 1', 'hello', '%end 1 2 1']);
  });

  test('accumulates across calls using remainder', () => {
    const r1 = parseLines('%beg');
    const r2 = parseLines(r1.remainder + 'in 1 2 1\r\n');
    expect(r2.lines).toEqual(['%begin 1 2 1']);
  });
});
