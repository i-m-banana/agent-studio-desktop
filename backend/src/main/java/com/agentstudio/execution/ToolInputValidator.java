package com.agentstudio.execution;

import java.util.Collection;
import java.util.Map;

import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ToolInputValidator {
    private final ObjectMapper objectMapper;

    public ToolInputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode validate(ToolDescriptor descriptor, String argumentsJson) {
        try {
            var arguments = objectMapper.readTree(
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            if (!arguments.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
            var schema = descriptor.inputSchema();
            var properties = schema.get("properties") instanceof Map<?, ?> map ? map : Map.of();
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                arguments.fieldNames().forEachRemaining(name -> {
                    if (!properties.containsKey(name)) throw new IllegalArgumentException("不支持的参数：" + name);
                });
            }
            if (schema.get("required") instanceof Collection<?> required) {
                required.forEach(name -> {
                    if (!arguments.hasNonNull(String.valueOf(name))) {
                        throw new IllegalArgumentException("缺少必填参数：" + name);
                    }
                });
            }
            for (var property : properties.entrySet()) {
                var name = String.valueOf(property.getKey());
                if (!arguments.has(name) || arguments.get(name).isNull() || !(property.getValue() instanceof Map<?, ?> rule)) continue;
                var expected = String.valueOf(rule.get("type"));
                var value = arguments.get(name);
                var valid = switch (expected) {
                    case "string" -> value.isTextual();
                    case "integer" -> value.isIntegralNumber();
                    case "number" -> value.isNumber();
                    case "boolean" -> value.isBoolean();
                    case "array" -> value.isArray();
                    case "object" -> value.isObject();
                    default -> true;
                };
                if (!valid) throw new IllegalArgumentException("参数 " + name + " 类型必须为 " + expected);
            }
            return arguments;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("工具参数不是有效 JSON", exception);
        }
    }
}
