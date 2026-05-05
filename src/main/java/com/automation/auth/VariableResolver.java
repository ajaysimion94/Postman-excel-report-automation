package com.automation.auth;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VariableResolver {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^}]+)}}");

    private VariableResolver() {
    }

    public static String resolve(String input, Map<String, String> variables) {
        if (input == null || input.isBlank()) {
            return input;
        }

        Matcher matcher = TEMPLATE.matcher(input);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = variables.getOrDefault(key, matcher.group(0));
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}