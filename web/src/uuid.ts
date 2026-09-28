const UUID_BYTES = 16;
const VERSION_BYTE = 6;
const VERSION_MASK = 0x0f;
const VERSION_FOUR = 0x40;
const VARIANT_BYTE = 8;
const VARIANT_MASK = 0x3f;
const RFC_VARIANT = 0x80;
const GROUP_BYTES = [4, 2, 2, 2, 6];

export function uuidV4(random: Pick<Crypto, 'getRandomValues'>): string {
  // getRandomValues remains available on ordinary HTTP; randomUUID requires a secure context.
  const bytes = random.getRandomValues(new Uint8Array(UUID_BYTES));
  bytes[VERSION_BYTE] = (bytes[VERSION_BYTE] & VERSION_MASK) | VERSION_FOUR;
  bytes[VARIANT_BYTE] = (bytes[VARIANT_BYTE] & VARIANT_MASK) | RFC_VARIANT;
  let offset = 0;
  return GROUP_BYTES.map(size => {
    const group = Array.from(bytes.subarray(offset, offset + size), byte => byte.toString(16).padStart(2, '0')).join('');
    offset += size;
    return group;
  }).join('-');
}
