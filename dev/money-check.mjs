// Behavioral Blackbox Atomic: decimal-string monetary rounding used by the usage panels.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const bundled = await build({ entryPoints: ['web/src/money.ts'], bundle: true, write: false, platform: 'node', format: 'esm' });
const { formatAmount, MoneyDigits, sumAmounts } = await import('data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].contents).toString('base64'));
assert.equal(MoneyDigits, 4);
const cases = [
  ['33.50446240000000049', 4, '33.5045'], ['33.50444999999999999', 4, '33.5044'], ['0.01', 4, '0.0100'], ['0.03', 4, '0.0300'],
  ['0.00005', 4, '0.0001'], ['0.00004999', 4, '0.0000'], ['1.99995', 4, '2.0000'], ['9.99995', 4, '10.0000'], ['0', 4, '0.0000'],
  ['5', 4, '5.0000'], ['123456789012345678901234567890.123456789', 4, '123456789012345678901234567890.1235'],
  ['2.5', 0, '3'], ['2.4', 0, '2'], ['0.125', 2, '0.13'], ['0.000000000000000001', 18, '0.000000000000000001'],
];
for (const [value, digits, expected] of cases) assert.equal(formatAmount(value, digits), expected, `${value} @ ${digits}`);
for (const invalid of ['', 'abc', '-1', '1.', '.5', '1e3', ' 1', '1,5']) assert.throws(() => formatAmount(invalid, 4), /Invalid decimal amount/, invalid);
const sums = [[[], '0'], [['0.01'], '0.01'], [['0.01', '0.02'], '0.03'], [['0.5', '0.50'], '1'], [['1.99995', '0.00005', '10'], '12'],
  [['123456789012345678901234567890.123456789', '0.000000001'], '123456789012345678901234567890.12345679'], [['100', '0'], '100']];
for (const [values, expected] of sums) assert.equal(sumAmounts(values), expected, values.join(' + '));
assert.throws(() => sumAmounts(['1', '-1']), /Invalid decimal amount/);
console.log('Monetary formatting: half-up decimal-string rounding on', cases.length, 'values and rejection of', 8, 'malformed inputs; exact sums on', sums.length, 'lists');
