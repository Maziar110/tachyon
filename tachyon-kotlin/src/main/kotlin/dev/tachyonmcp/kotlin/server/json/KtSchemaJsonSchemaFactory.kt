// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.json

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory
import me.kpavlov.kt.schema.generator.json.ReflectionClassJsonSchemaGenerator

/**
 * [JsonSchemaFactory] keyed by [Class], registered via `META-INF/services` so the reified
 * `typedTool<In, Out>(...)` DSL overload (which calls `JsonSchema.from(Class, Class::class)`)
 * resolves real generated schemas here, backed by kt-schema's
 * [ReflectionClassJsonSchemaGenerator].
 *
 * `tachyon-kotlin` declares `kt-schema-generator-json-jvm` as an `optional` Maven dependency: this
 * class is always registered via `META-INF/services`, but only loads successfully when a consumer
 * also puts kt-schema on their own classpath. When it's absent,
 * [dev.tachyonmcp.api.json.JsonSchema]'s `ServiceLoader`-based discovery silently skips this
 * provider, so `typedTool` isn't usable but the rest of the server is unaffected.
 */
internal class KtSchemaJsonSchemaFactory : JsonSchemaFactory<Class<*>> {
    companion object {
        @JvmField
        val INSTANCE: KtSchemaJsonSchemaFactory = KtSchemaJsonSchemaFactory()

        /** Provider factory used by [java.util.ServiceLoader] to obtain the singleton. */
        @JvmStatic
        fun provider(): KtSchemaJsonSchemaFactory = INSTANCE
    }

    private val generator = ReflectionClassJsonSchemaGenerator.Default

    override fun sourceType(): Class<Class<*>> {
        @Suppress("UNCHECKED_CAST")
        return Class::class.java
    }

    override fun toJsonSchema(source: Class<*>): JsonSchema =
        JsonSchema.of(generator.generateSchemaString(source.kotlin))
}
