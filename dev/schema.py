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
            del schema["description"]

    def normalize(value):
        if isinstance(value, list):
            return [normalize(child) for child in value]
        if not isinstance(value, dict):
            return value
        result = {key: normalize(child) for key, child in value.items()}
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

    target = root / "generated/resources/cq-schemas.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(normalize(schemas), sort_keys=True, separators=(",", ":")) + "\n")
