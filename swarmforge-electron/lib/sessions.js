const fs = require('fs');

function parseSessions(tsvPath) {
  try {
    return fs.readFileSync(tsvPath, 'utf8')
      .split('\n')
      .filter(Boolean)
      .flatMap(line => {
        const cols = line.split('\t');
        if (cols.length < 3) return [];
        return [{ role: cols[1], session: cols[2] }];
      });
  } catch (err) {
    if (err.code === 'ENOENT') return [];
    throw err;
  }
}

module.exports = { parseSessions };
