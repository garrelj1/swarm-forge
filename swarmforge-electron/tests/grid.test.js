const { computeGrid } = require('../lib/grid');

test('1 agent = 1 col, 1 row', () => {
  expect(computeGrid(1)).toEqual({ cols: 1, rows: 1 });
});

test('2 agents = 2 cols, 1 row', () => {
  expect(computeGrid(2)).toEqual({ cols: 2, rows: 1 });
});

test('3 agents = 2 cols, 2 rows', () => {
  expect(computeGrid(3)).toEqual({ cols: 2, rows: 2 });
});

test('4 agents = 2 cols, 2 rows', () => {
  expect(computeGrid(4)).toEqual({ cols: 2, rows: 2 });
});

test('5 agents = 3 cols, 2 rows', () => {
  expect(computeGrid(5)).toEqual({ cols: 3, rows: 2 });
});

test('6 agents = 3 cols, 2 rows', () => {
  expect(computeGrid(6)).toEqual({ cols: 3, rows: 2 });
});
