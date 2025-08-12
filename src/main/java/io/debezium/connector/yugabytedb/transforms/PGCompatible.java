package io.debezium.connector.yugabytedb.transforms;

import java.util.Map;
import java.util.Objects;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.util.Pair;

public class PGCompatible<R extends ConnectRecord<R>> implements Transformation<R> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PGCompatible.class);
    // Configuration key to enable including only columns that were changed in the original
    // database event. When set to true, the transform will omit unchanged columns from the
    // flattened record, similar to the behaviour of YBExtractNewRecordState.
    public static final String INCLUDE_CHANGED_COLUMNS = "include.changed.columns";

    private boolean includeChangedColumns = false;

    @Override
    public R apply(final R record) {
        if (record == null || (record.value() != null && !(record.value() instanceof Struct))) {
            return record;
        }

        Pair<Schema, Struct> p = getUpdatedValueAndSchema(record.keySchema(), (Struct) record.key());
        Schema updatedSchemaForKey = p.getFirst();
        Struct updatedValueForKey = p.getSecond();

        Schema updatedSchemaForValue = null;
        Struct updatedValueForValue = null;
        if (record.value() != null) {
            Pair<Schema, Struct> val = getUpdatedValueAndSchema(record.valueSchema(), (Struct) record.value());
            updatedSchemaForValue = val.getFirst();
            updatedValueForValue = val.getSecond();
        }

        return record.newRecord(record.topic(), record.kafkaPartition(), updatedSchemaForKey, updatedValueForKey, updatedSchemaForValue, updatedValueForValue, record.timestamp());
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef().define(
                INCLUDE_CHANGED_COLUMNS,
                ConfigDef.Type.BOOLEAN,
                false,
                ConfigDef.Importance.MEDIUM,
                "When true, the SMT will omit columns that were not changed in the original database event.");
    }

    @Override
    public void close() {
    }

    private boolean isValueSetStruct(Field field) {
        return field.schema().fields().size() == 2
                && (Objects.equals(field.schema().fields().get(0).name(), "value")
                && Objects.equals(field.schema().fields().get(1).name(), "set"));
    }

    private Schema makeUpdatedSchema(Schema schema) {
        final SchemaBuilder builder = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());

        if (schema.isOptional()) {
            builder.optional();
        } else {
            builder.required();
        }

        for (Field field : schema.fields()) {
            LOGGER.debug("Considering {}", field.name());
            if (field.schema().type() == Type.STRUCT) {
                LOGGER.debug("Field is a struct");
                if (isValueSetStruct(field)) {
                    LOGGER.debug("Field is valueset");
                    builder.field(field.name(), field.schema().field("value").schema());
                } else {
                    LOGGER.debug("Field is not valueset");
                    builder.field(field.name(), makeUpdatedSchema(field.schema()));
                }
            } else {
                LOGGER.debug("Field is not a struct");
                builder.field(field.name(), field.schema());
            }
        }

        return builder.build();
    }

    /**
     * Creates an updated schema that optionally omits columns that were not present (i.e. not changed)
     * in the supplied value struct. A column is considered "changed" when the corresponding
     * value-set struct is non-null in the change event emitted by the connector.
     *
     * @param schema The original Connect schema
     * @param value  The Connect value corresponding to the schema
     * @return A potentially reduced schema that contains only changed columns when
     *         {@link #includeChangedColumns} is true
     */
    private Schema makeUpdatedSchema(Schema schema, Struct value) {
        if (!includeChangedColumns) {
            // Fallback to default behaviour when the feature is disabled
            return makeUpdatedSchema(schema);
        }

        final SchemaBuilder builder = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());

        if (schema.isOptional()) {
            builder.optional();
        } else {
            builder.required();
        }

        for (Field field : schema.fields()) {
            if (field.schema().type() == Type.STRUCT && isValueSetStruct(field)) {
                // Only include this column if it exists in the incoming value struct (i.e. was changed)
                if (value.get(field.name()) != null) {
                    builder.field(field.name(), field.schema().field("value").schema());
                }
            }
            else {
                // Preserve non-value-set fields as-is (e.g. envelope metadata like "op", "source", etc.)
                builder.field(field.name(), field.schema());
            }
        }

        return builder.build();
    }

    private Struct makeUpdatedValue(Schema updatedSchema, Struct value) {
        final Struct updatedValue = new Struct(updatedSchema);

        for (Field field : updatedSchema.fields()) {
            LOGGER.debug("Considering value {}", field.name());
            if (field.schema().type() == Type.STRUCT) {
                LOGGER.debug("Value is a struct");
                Struct fieldValue = (Struct) value.get(field.name());
                if (isValueSetStruct(field) && fieldValue != null) {
                    LOGGER.debug("value is valueset");
                    updatedValue.put(field.name(), fieldValue.get("value"));
                } else if (fieldValue != null) {
                    LOGGER.debug("value is not valueset");
                    updatedValue.put(field.name(), makeUpdatedValue(updatedSchema.field(field.name()).schema(), fieldValue));
                }
            } else {
                LOGGER.debug("value is not a struct");
                updatedValue.put(field.name(), value.get(field.name()));
            }
        }

        return updatedValue;
    }

    public Pair<Schema, Struct> getUpdatedValueAndSchema(Schema schema, Struct value) {
        LOGGER.debug("Original Schema as json: " + io.debezium.data.SchemaUtil.asString(schema));
        Schema updatedSchema = includeChangedColumns ? makeUpdatedSchema(schema, value) : makeUpdatedSchema(schema);
        LOGGER.debug("Updated schema as json: " + io.debezium.data.SchemaUtil.asString(updatedSchema));

        LOGGER.debug("Original value as json: {}", io.debezium.data.SchemaUtil.asDetailedString(value));
        Struct updatedValue = makeUpdatedValue(updatedSchema, value);
        LOGGER.debug("Update value as json: {}", io.debezium.data.SchemaUtil.asDetailedString(updatedValue));

        return new org.yb.util.Pair<>(updatedSchema, updatedValue);
    }

    @Override
    public void configure(Map<String, ?> configs) {
        Object cfg = configs.get(INCLUDE_CHANGED_COLUMNS);
        if (cfg != null) {
            includeChangedColumns = Boolean.parseBoolean(cfg.toString());
        }
    }
}
