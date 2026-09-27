import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { Probe_JsonCodec, ApiError_JsonCodec } from '../.work/contracts.mjs';
import * as contracts from '../.work/contracts.mjs';
import { BaboonCodecContext } from '../.work/contract-runtime.mjs';
import { AjvJsonSchemaValidator } from '@modelcontextprotocol/sdk/validation/ajv';
import { ToolSchema } from '@modelcontextprotocol/sdk/types.js';

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
const queryJson = JSON.parse(await readFile(`${directory}/scala-query.json`, 'utf8'));
const query = contracts.QueryExpression_JsonCodec.instance.decode(context, queryJson);
const encodedQuery = contracts.QueryExpression_JsonCodec.instance.encode(context, query);
assert.deepEqual(encodedQuery, queryJson);
await writeFile(`${directory}/typescript-query.json`, JSON.stringify(encodedQuery));
const definitions = JSON.parse(await readFile('generated/resources/cq-schemas.json', 'utf8'));
const validator = new AjvJsonSchemaValidator();
assert.equal(validator.getValidator({ ...definitions.cq_api_QueryExpression, $defs: definitions })(encodedQuery).valid, true);
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
  ToolSchema.parse(tool);
  const input = fixture(tool.inputSchema, tool.inputSchema.$defs);
  assert.equal(validator.getValidator(tool.inputSchema)(input).valid, true, tool.name);
  for (const branch of tool.outputSchema.oneOf) {
    const output = fixture(branch, tool.outputSchema.$defs);
    assert.equal(validator.getValidator(tool.outputSchema)(output).valid, true, tool.name);
  }
}
for (const [name, input, output] of [['dispatch', 'DispatchCommand', 'DispatchReply'], ['workspace', 'WorkspaceCommand', 'WorkspaceReply']]) {
  ToolSchema.parse({ name, inputSchema: definitions[`cq_api_${input}`], outputSchema: definitions[`cq_api_${output}`] });
}
const reports = JSON.parse(await readFile(`${directory}/child-report-schemas.json`, 'utf8'));
for (const [tag, schema] of Object.entries(reports)) {
  assert.equal(schema.type, 'object');
  assert.equal(schema.additionalProperties, false);
  assert.deepEqual(schema.required, [tag]);
  assert.equal('oneOf' in schema, false);
  assert.equal('anyOf' in schema, false);
  const sample = fixture(schema, schema.$defs);
  const check = validator.getValidator(schema);
  assert.equal(check(sample).valid, true);
  assert.equal(check(fixture(reports[tag === 'Work' ? 'Review' : 'Work'], definitions)).valid, false);
  assert.equal(check({ ...sample, extra: true }).valid, false);
  assert.equal(check({ [tag]: { members: [] } }).valid, false);
  assert.deepEqual(contracts.ChildReport_JsonCodec.instance.encode(context, contracts.ChildReport_JsonCodec.instance.decode(context, sample)), sample);
}
const guides = JSON.parse(await readFile(`${directory}/native-guides.json`, 'utf8'));
for (const [role, guide] of Object.entries(guides)) {
  const references = new Map();
  function equivalent(expected, actual) {
    if (Array.isArray(expected)) {
      assert(Array.isArray(actual));
      assert.equal(actual.length, expected.length);
      expected.forEach((value, index) => equivalent(value, actual[index]));
    } else if (expected !== null && typeof expected === 'object') {
      assert.deepEqual(Object.keys(actual).sort(), Object.keys(expected).sort());
      for (const [key, value] of Object.entries(expected)) {
        if (key !== '$ref') equivalent(value, actual[key]);
        else {
          const source = value.replace('#/$defs/', '');
          const alias = actual[key].replace('#/$defs/', '');
          assert(definitions[source] && guide.$defs[alias]);
          if (references.has(alias)) assert.equal(references.get(alias), source);
          else {
            assert(!Array.from(references.values()).includes(source));
            references.set(alias, source);
            equivalent(definitions[source], guide.$defs[alias]);
          }
        }
      }
    } else assert.deepEqual(actual, expected);
  }
  assert.deepEqual(Object.keys(guide.tools).sort(), role === 'Governor' ? ['cq.change', 'cq.read', 'cq.usage', 'cq_host.dispatch'] : ['cq.read', 'cq.usage']);
  for (const [name, schema] of Object.entries(guide.tools)) {
    const type = { 'cq.read': 'ReadInput', 'cq.change': 'ChangeInput', 'cq.usage': 'UsageInput', 'cq_host.dispatch': 'DispatchCommand' }[name];
    equivalent(definitions[`cq_api_${type}`], schema);
    const sample = fixture(schema, guide.$defs);
    const codec = contracts[`${type}_JsonCodec`].instance;
    assert.deepEqual(codec.encode(context, codec.decode(context, sample)), sample);
    assert.equal(validator.getValidator({ ...schema, $defs: guide.$defs })(sample).valid, true);
  }
  assert.equal(references.size, Object.keys(guide.$defs).length);
}
const content = validator.getValidator({ ...definitions.cq_api_Content, $defs: definitions });
assert.equal(content({ Task: { status: 'Ready', acceptance: ['test'], result: null, validation: [] } }).valid, true);
assert.equal(content({ Task: { status: 'Ready', acceptance: ['test'], validation: [] } }).valid, false);
assert.equal(content({ status: 'Ready', acceptance: ['test'], validation: [] }).valid, false);
assert.equal(content({ Task: { status: 'Ready', acceptance: ['test'], validation: [] }, Question: {} }).valid, false);
const dispatchSchema = definitions.cq_api_DispatchRequest;
const dispatch = validator.getValidator({ ...dispatchSchema, $defs: definitions });
const request = fixture(dispatchSchema, definitions);
assert.equal(dispatch(request).valid, true);
assert.equal(dispatch({ ...request, prompt: 'Copied narrative' }).valid, false);
assert.equal(dispatch({ ...request, limits: { ...request.limits, prompt: 'Nested narrative' } }).valid, false);
assert.equal(dispatch({ ...request, work: { Reviewer: { prompt: 'Copied narrative' } } }).valid, false);
const { previous, ...withoutPrevious } = request;
assert.equal(dispatch(withoutPrevious).valid, false);
assert.equal(dispatch({ ...request, members: [] }).valid, false);
assert.equal(dispatch({ ...request, members: Array.from({ length: 17 }, (_, index) => ({
  ...request.members[0], id: { ...request.members[0].id, number: String(index + 1) },
})) }).valid, false);
assert.equal(dispatch({ ...request, artifacts: Array(9).fill(request.artifacts[0]) }).valid, false);
console.log(`TypeScript codec round trips, ${Object.keys(definitions).length} schema definitions and ${tools.length} MCP capabilities passed`);
