function computeGrid(n) {
  const cols = Math.ceil(Math.sqrt(n));
  const rows = Math.ceil(n / cols);
  return { cols, rows };
}

module.exports = { computeGrid };
