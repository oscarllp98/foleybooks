package com.foleybooks.auth.common;

/**
 * One entry of the {@code errors[]} array carried by 400 validation ProblemDetails
 * (AGENTS.md §6): the offending field and the failed rule's message.
 */
public record ValidationError(String field, String message) {
}
