/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.json;

import dev.tachyonmcp.api.json.spi.JsonSchemaFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

final class JsonSchemas {

    private JsonSchemas() {}

    static <T> JsonSchema from(T source, Class<T> type) {
        return factoryFor(type).toJsonSchema(source);
    }

    @SuppressWarnings("unchecked")
    private static <T> JsonSchemaFactory<T> factoryFor(Class<T> type) {
        var factory = Holder.FACTORIES.get(type);
        if (factory == null) {
            throw new IllegalStateException("No JsonSchemaFactory<" + type.getName()
                    + "> implementation found on the classpath. Register one via META-INF/services/"
                    + JsonSchemaFactory.class.getName() + ".");
        }
        return (JsonSchemaFactory<T>) factory;
    }

    private static final class Holder {
        static final Map<Class<?>, JsonSchemaFactory<?>> FACTORIES = discover();

        private static Map<Class<?>, JsonSchemaFactory<?>> discover() {
            var map = new HashMap<Class<?>, JsonSchemaFactory<?>>();
            // A provider class may fail to load when its module treats the backing
            // implementation as an optional runtime dependency (e.g. tachyon-kotlin's
            // KtSchemaJsonSchemaFactory). Skip it instead of failing discovery for every provider.
            var providers = ServiceLoader.load(JsonSchemaFactory.class).iterator();
            while (providers.hasNext()) {
                JsonSchemaFactory<?> factory;
                try {
                    factory = providers.next();
                } catch (ServiceConfigurationError e) {
                    continue;
                }
                var existing = map.putIfAbsent(factory.sourceType(), factory);
                if (existing != null) {
                    throw new IllegalStateException("Duplicate JsonSchemaFactory<"
                            + factory.sourceType().getName() + "> implementations found: "
                            + existing.getClass().getName() + " and "
                            + factory.getClass().getName());
                }
            }
            return Map.copyOf(map);
        }
    }
}
