/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.json.JsonSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormInputRequestTest {

    @Test
    void ofAcceptsJsonSchemaDirectly() {
        var schema = JsonSchema.unchecked("{\"type\":\"object\"}");

        var request = FormInputRequest.of("Enter details", schema);

        assertThat(request.message()).isEqualTo("Enter details");
        assertThat(request.requestedSchema()).isSameAs(schema);
    }

    @Test
    @SuppressWarnings("deprecation")
    void ofFromMapBuildsEquivalentSchemaViaMapJsonFactory() {
        Map<String, Object> schemaMap = new LinkedHashMap<>();
        schemaMap.put("type", "object");
        schemaMap.put("properties", Map.of("name", Map.of("type", "string")));
        schemaMap.put("required", List.of("name"));

        var request = FormInputRequest.of("Enter details", schemaMap);

        assertThatJson(request.requestedSchema().json()).isEqualTo("""
                        {
                          "type": "object",
                          "properties": {"name": {"type": "string"}},
                          "required": ["name"]
                        }
                        """);
    }

    @Test
    void builderAcceptsJsonSchemaDirectly() {
        var schema = JsonSchema.objectSchema();

        var request = FormInputRequest.builder()
                .message("Enter details")
                .requestedSchema(schema)
                .build();

        assertThat(request.requestedSchema()).isSameAs(schema);
    }

    @Test
    @SuppressWarnings("deprecation")
    void builderAcceptsMapAndRoutesThroughJsonSchemaFrom() {
        var request = FormInputRequest.builder()
                .message("Enter details")
                .requestedSchema(Map.of("type", "object"))
                .build();

        assertThatJson(request.requestedSchema().json()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void checkRejectsBlankMessage() {
        assertThatThrownBy(() -> FormInputRequest.of("   ", JsonSchema.objectSchema()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
    }

    @Test
    void builderFromCopiesMessageAndSchema() {
        var original = FormInputRequest.of("Enter details", JsonSchema.objectSchema());

        var copy = FormInputRequest.builder().from(original).build();

        assertThat(copy.message()).isEqualTo(original.message());
        assertThat(copy.requestedSchema()).isEqualTo(original.requestedSchema());
    }
}
