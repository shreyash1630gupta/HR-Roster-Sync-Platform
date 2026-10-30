package com.moveinsync.roster.validation;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
@AllArgsConstructor
public class ValidationResult {

    private final boolean valid;
    private final List<String> errors;

    public static ValidationResult ok() {
        return new ValidationResult(true, Collections.emptyList());
    }

    public static ValidationResult fail(List<String> errors) {
        return new ValidationResult(false, errors);
    }

    public static ValidationResult fail(String error) {
        List<String> errors = new ArrayList<>();
        errors.add(error);
        return new ValidationResult(false, errors);
    }
}

