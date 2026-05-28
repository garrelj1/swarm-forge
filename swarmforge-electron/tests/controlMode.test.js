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
