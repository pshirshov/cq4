import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { Probe_JsonCodec, ApiError_JsonCodec } from '../.work/contracts.mjs';
import * as contracts from '../.work/contracts.mjs';
import { BaboonCodecContext } from '../.work/contract-runtime.mjs';
import { AjvJsonSchemaValidator } from '@modelcontextprotocol/sdk/validation/ajv';

const directory = process.argv[2];
assert.ok(directory, 'Expected fixture directory');
const context = BaboonCodecContext.Default;
const probe = Probe_JsonCodec.instance.decode(context, JSON.parse(await readFile(`${directory}/scala-probe.json`, 'utf8')));
assert.equal(probe.revision.value, 9007199254740993n);
assert.equal(probe.text, 'round trip λ');
const error = ApiError_JsonCodec.instance.decode(context, JSON.parse(await readFile(`${directory}/scala-error.json`, 'utf8')));
assert.equal(error.expected.value, 9007199254740993n);
assert.equal(error.actual.value, 9223372036854775807n);
await writeFile(`${directory}/typescript-probe.json`, JSON.stringify(Probe_JsonCodec.instance.encode(context, probe)));
await writeFile(`${directory}/typescript-error.json`, JSON.stringify(ApiError_JsonCodec.instance.encode(context, error)));
const definitions = JSON.parse(await readFile('generated/resources/cq-schemas.json', 'utf8'));
const validator = new AjvJsonSchemaValidator();
const validate = validator.getValidator({ ...definitions.cq_api_Probe, $defs: definitions });
const encoded = Probe_JsonCodec.instance.encode(context, probe);
assert.equal(validate(encoded).valid, true);
assert.equal(validate({ ...encoded, revision: { value: 9007199254740992 } }).valid, false);
assert.equal(validate({ ...encoded, project: { value: 'invalid' } }).valid, false);
assert.equal(validate({ project: encoded.project, revision: encoded.revision }).valid, false);

function fixture(schema, defs) {
  if (schema.$ref) return fixture(defs[schema.$ref.replace('#/$defs/', '')], defs);
  if (schema.oneOf) return fixture(schema.oneOf[0], defs);
  if (schema.enum) return schema.enum[0];
  switch (schema.type) {
    case 'object': return Object.fromEntries(Object.entries('properties' in schema ? schema.properties : {}).map(([key, value]) => [key, fixture(value, defs)]));
    case 'array': return [fixture(schema.items, defs)];
    case 'string': return schema.format === 'uuid' ? '00000000-0000-0000-0000-000000000001' : schema.pattern ? '9007199254740993' : 'fixture λ';
    case 'integer': return 1;
    case 'boolean': return true;
    case 'null': return null;
    default: throw new Error(`Unhandled schema ${JSON.stringify(schema)}`);
  }
}
for (const [name, schema] of Object.entries(definitions)) {
  const codec = contracts[`${name.replace('cq_api_', '')}_JsonCodec`];
  assert.ok(codec, `Missing codec for ${name}`);
  const samples = schema.oneOf ? schema.oneOf.map(branch => fixture(branch, definitions)) : [fixture(schema, definitions)];
  const check = validator.getValidator({ ...schema, $defs: definitions });
  for (const sample of samples) {
    const encoded = codec.instance.encode(context, codec.instance.decode(context, sample));
    assert.equal(check(encoded).valid, true, `${name}: ${JSON.stringify(check(encoded))}`);
  }
}
const tools = JSON.parse(await readFile(`${directory}/mcp-tools.json`, 'utf8'));
for (const tool of tools) {
  const input = fixture(tool.inputSchema, tool.inputSchema.$defs);
  assert.equal(validator.getValidator(tool.inputSchema)(input).valid, true, tool.name);
  for (const branch of tool.outputSchema.oneOf) {
    const output = fixture(branch, tool.outputSchema.$defs);
    assert.equal(validator.getValidator(tool.outputSchema)(output).valid, true, tool.name);
  }
}
const content = validator.getValidator({ ...definitions.cq_api_Content, $defs: definitions });
assert.equal(content({ Task: { status: 'Ready', acceptance: ['test'], validation: [] } }).valid, true);
assert.equal(content({ status: 'Ready', acceptance: ['test'], validation: [] }).valid, false);
assert.equal(content({ Task: { status: 'Ready', acceptance: ['test'], validation: [] }, Question: {} }).valid, false);
console.log(`TypeScript codec round trips, ${Object.keys(definitions).length} schema definitions and ${tools.length} MCP capabilities passed`);
