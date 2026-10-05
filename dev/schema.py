import json
from pathlib import Path


def generate_schemas(root: Path):
    source = root / "generated/openapi/cq_api/v0_1_0/openapi.json"
    schemas = json.loads(source.read_text())["components"]["schemas"]
    for name, schema in schemas.items():
        if schema.get("description", "").startswith("ADT discriminator-wrapper encoding"):
            branches = schema["oneOf"]
            wrapped = []
            for branch in branches:
                prefix = "#/components/schemas/" + name + "_"
                if set(branch) != {"$ref"} or not branch["$ref"].startswith(prefix):
                    raise RuntimeError(f"Baboon ADT schema patch no longer matches {name}")
                tag = branch["$ref"].removeprefix(prefix)
                wrapped.append({"type": "object", "required": [tag], "properties": {tag: branch},
                                "additionalProperties": False})
            schema["oneOf"] = wrapped
            schema["type"] = "object"
            del schema["description"]

    def normalize(value):
        if isinstance(value, list):
            return [normalize(child) for child in value]
        if not isinstance(value, dict):
            return value
        # Baboon's OpenAPI output describes a map whose key is not a string as an array of key/value pairs, while its Scala and
        # TypeScript JSON codecs both write such a map as an object keyed by the key's text. The schema follows the codecs.
        entry = value.get("items")
        if value.get("type") == "array" and isinstance(entry, dict) and "$ref" not in entry and set(entry.get("properties", {})) == {"key", "value"}:
            if set(value) != {"type", "items"} or set(entry) != {"type", "required", "properties"}:
                raise RuntimeError("Baboon map schema patch no longer matches")
            return {"type": "object", "additionalProperties": normalize(entry["properties"]["value"])}
        result = {key: normalize(child) for key, child in value.items()}
        if result.get("type") == "object" and ("properties" in result or set(result) == {"type"}):
            result["additionalProperties"] = False
            if "properties" in result:
                result["required"] = list(result["properties"])
        if "$ref" in result:
            result["$ref"] = result["$ref"].replace("#/components/schemas/", "#/$defs/")
        if result.get("format") == "int64":
            del result["format"]
            result["pattern"] = "^-?(0|[1-9][0-9]*)$"
        if result.get("format") == "int32":
            del result["format"]
            result["minimum"] = -2147483648
            result["maximum"] = 2147483647
        return result

    schemas = normalize(schemas)
    bounds = {
        "DispatchRequest": {
            "members": {"minItems": 1, "maxItems": 16, "uniqueItems": True},
            "guidance": {"maxItems": 16, "uniqueItems": True},
            "artifacts": {"maxItems": 8, "uniqueItems": True},
        },
        "ChildReport_Work": {"members": {"minItems": 1, "maxItems": 16}},
        "ChildReport_Review": {"members": {"minItems": 1, "maxItems": 16}},
        "ChildReport_Plan": {"members": {"minItems": 1, "maxItems": 16}},
        "ChildReport_Evidence": {"members": {"minItems": 1, "maxItems": 16}},
        "LedgerProposal": {"mutations": {"minItems": 1, "maxItems": 64}, "reason": {"minLength": 1, "maxLength": 300}},
        "ProposedMutation_Produce": {"drafts": {"minItems": 1, "maxItems": 64}},
        "PlanMember": {"summary": {"minLength": 1, "maxLength": 8192}},
        "EvidenceMember": {"summary": {"minLength": 1, "maxLength": 8192}, "evidence": {"maxItems": 32},
                           "uncertainties": {"maxItems": 32, "items": {"type": "string", "minLength": 1, "maxLength": 8192}},
                           "requestedProbes": {"maxItems": 32, "items": {"type": "string", "minLength": 1, "maxLength": 8192}}},
        "WorkMember": {"summary": {"minLength": 1, "maxLength": 8192}},
        "ReviewMember": {"findings": {"maxItems": 32, "items": {"type": "string", "minLength": 1, "maxLength": 8192}}},
    }
    for name, fields in bounds.items():
        for field, limits in fields.items():
            schemas[f"cq_api_{name}"]["properties"][field].update(limits)

    target = root / "generated/resources/cq-schemas.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(schemas, sort_keys=True, separators=(",", ":")) + "\n")
