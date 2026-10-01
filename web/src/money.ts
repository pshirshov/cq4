// Displayed fractional digits for every currency: token prices are sub-cent, so two digits would hide most costs.
export const MoneyDigits = 4;

// Rounds a nonnegative decimal string half-up to the given number of fractional digits without going through floating point.
export function formatAmount(value: string, digits: number): string {
  const match = /^([0-9]+)(?:\.([0-9]+))?$/.exec(value);
  if (match === null) throw new Error(`Invalid decimal amount: ${value}`);
  const whole = match[1] as string;
  const fraction = (match[2] === undefined ? '' : match[2]).padEnd(digits + 1, '0');
  const rounded = BigInt(whole + fraction.slice(0, digits)) + (fraction.charAt(digits) >= '5' ? 1n : 0n);
  const text = rounded.toString().padStart(digits + 1, '0');
  return digits === 0 ? text : `${text.slice(0, -digits)}.${text.slice(-digits)}`;
}

// Adds nonnegative decimal strings exactly; the result has no trailing fractional zeros, like the amounts the service reports.
export function sumAmounts(values: readonly string[]): string {
  const parts = values.map(value => {
    const match = /^([0-9]+)(?:\.([0-9]+))?$/.exec(value);
    if (match === null) throw new Error(`Invalid decimal amount: ${value}`);
    return { whole: match[1] as string, fraction: match[2] === undefined ? '' : match[2] };
  });
  const scale = parts.reduce((most, part) => Math.max(most, part.fraction.length), 0);
  const total = parts.reduce((sum, part) => sum + BigInt(part.whole + part.fraction.padEnd(scale, '0')), 0n).toString().padStart(scale + 1, '0');
  const whole = total.slice(0, total.length - scale); const fraction = total.slice(total.length - scale).replace(/0+$/, '');
  return fraction === '' ? whole : `${whole}.${fraction}`;
}
