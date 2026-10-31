package dev.sourcerer.core;

/** Authenticated caller. {@code null} is used for anonymous. */
public record Principal(long userId, String username) {}
