package com.multind.bitpongo.exchange;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Applied to exchange secret columns; plaintext legacy values pass through on read. */
@Converter
public class ExchangeSecretConverter implements AttributeConverter<String, String> {
    @Override
    public String convertToDatabaseColumn(String attribute) {
        return ExchangeCredentialCrypto.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return ExchangeCredentialCrypto.decrypt(dbData);
    }
}
