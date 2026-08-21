package com.techverito.banking.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Currency;

@Converter
public class CurrencyAttributeConverter implements AttributeConverter<Currency, String> {

    @Override
    public String convertToDatabaseColumn(Currency attribute) {
        return attribute != null ? attribute.getCurrencyCode() : null;
    }

    @Override
    public Currency convertToEntityAttribute(String dbData) {
        return dbData != null ? Currency.getInstance(dbData) : null;
    }
}
