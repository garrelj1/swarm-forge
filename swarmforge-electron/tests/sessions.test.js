const { parseSessions } = require('../lib/sessions');
const path = require('path');
const fs = require('fs');
const os = require('os');

function writeTsv(lines) {
  const f = path.join(os.tmpdir(), `sf-test-${Date.now()}.tsv`);
  fs.writeFileSync(f, lines.join('\n'));
  return f;
}

test('parses role and session from tsv', () => {
  const f = writeTsv([
    '1\tspecifier\tswarmforge-myproject-specifier\tSpecifier\tclaude',
    '2\tcoder\tswarmforge-myproject-coder\tCoder\tclaude',
  ]);
  const sessions = parseSessions(f);
  expect(sessions).toEqual([
    { role: 'specifier', session: 'swarmforge-myproject-specifier' },
    { role: 'coder',     session: 'swarmforge-myproject-coder' },
  ]);
});

test('returns empty array when file is missing', () => {
  expect(parseSessions('/nonexistent/path.tsv')).toEqual([]);
});

test('skips malformed lines', () => {
  const f = writeTsv(['bad line', '1\tcoder\tswarmforge-coder\tCoder\tclaude']);
  expect(parseSessions(f)).toHaveLength(1);
});
