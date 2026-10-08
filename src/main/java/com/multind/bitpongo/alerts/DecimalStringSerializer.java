package com.multind.bitpongo.alerts;

import java.math.BigDecimal;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/** Preserve exact editable prices across JSON and JavaScript boundaries. */
public final class DecimalStringSerializer extends ValueSerializer<BigDecimal> {
    @Override public void serialize(BigDecimal value, JsonGenerator output, SerializationContext context) {
        output.writeString(value.toPlainString());
    }
}
