package com.techverito.banking.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDate;
import java.time.Period;

public class AgeAtLeastValidator implements ConstraintValidator<AgeAtLeast, LocalDate> {

    private int minAge;

    @Override
    public void initialize(AgeAtLeast constraintAnnotation) {
        this.minAge = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return Period.between(value, LocalDate.now()).getYears() >= minAge;
    }
}
