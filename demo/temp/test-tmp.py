

from pydantic import create_model
from pydantic.json_schema import GenerateJsonSchema

tool_name = "test"

fields = {
    'a' : (str, ...),
    'b' : (int, 0)
}

# 动态创建 pydantic model，生成 schema
ArgsModel = create_model(f"{tool_name}_Args", **fields)
schema = ArgsModel.model_json_schema(schema_generator=GenerateJsonSchema)

import json
print(json.dumps(schema, ensure_ascii=False, indent=2))