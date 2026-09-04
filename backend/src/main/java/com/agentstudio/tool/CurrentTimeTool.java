package com.agentstudio.tool;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class CurrentTimeTool implements AgentTool {

    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "current_time", "查询当前时间", "返回指定 IANA 时区的当前日期、时间和 UTC 偏移。",
            "BUILTIN", "READ", "LOW", 3,
            Map.of(
                    "type", "object",
                    "properties", Map.of("zoneId", Map.of(
                            "type", "string",
                            "description", "IANA 时区，例如 Asia/Shanghai；省略时使用 Asia/Shanghai")),
                    "required", List.of(),
                    "additionalProperties", false));

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String execute(JsonNode arguments) {
        var fields = arguments.fieldNames();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!"zoneId".equals(field)) throw new IllegalArgumentException("不支持的参数：" + field);
        }
        var requestedZone = arguments.path("zoneId").asText("Asia/Shanghai").trim();
        if (requestedZone.isBlank()) requestedZone = "Asia/Shanghai";
        try {
            var now = ZonedDateTime.now(ZoneId.of(requestedZone));
            return "{\"zoneId\":\"" + now.getZone().getId() + "\",\"dateTime\":\""
                    + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now) + "\"}";
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("无效时区：" + requestedZone);
        }
    }
}
