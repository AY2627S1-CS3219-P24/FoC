package com.cs3219.foc.order.model.dto;

import java.util.List;

// An errand's chat, oldest first
public record ChatDto(List<ErrandMessageDto> messages) {}
