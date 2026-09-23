package com.cs3219.foc.user.model.dto;

import java.util.Map;

public record ValidationErrorResponse(String message, Map<String, String> fieldErrors) {}
