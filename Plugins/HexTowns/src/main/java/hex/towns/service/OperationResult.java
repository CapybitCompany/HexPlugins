package hex.towns.service;

import hex.core.api.ui.UiTokens;

public record OperationResult(boolean success, String templateKey, UiTokens tokens) {
    public static OperationResult ok(String templateKey) {
        return new OperationResult(true, templateKey, UiTokens.of("empty", ""));
    }

    public static OperationResult ok(String templateKey, UiTokens tokens) {
        return new OperationResult(true, templateKey, tokens);
    }

    public static OperationResult fail(String templateKey) {
        return new OperationResult(false, templateKey, UiTokens.of("empty", ""));
    }

    public static OperationResult fail(String templateKey, UiTokens tokens) {
        return new OperationResult(false, templateKey, tokens);
    }
}