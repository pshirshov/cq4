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
