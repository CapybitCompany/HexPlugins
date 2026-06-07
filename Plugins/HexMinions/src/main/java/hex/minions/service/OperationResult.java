package hex.minions.service;

import hex.core.api.ui.UiTokens;

public record OperationResult(boolean success, String messageKey, UiTokens tokens) {
    public static OperationResult ok(String messageKey) {
        return new OperationResult(true, messageKey, new UiTokens());
    }

    public static OperationResult ok(String messageKey, UiTokens tokens) {
        return new OperationResult(true, messageKey, tokens == null ? new UiTokens() : tokens);
    }

    public static OperationResult fail(String messageKey) {
        return new OperationResult(false, messageKey, new UiTokens());
    }

    public static OperationResult fail(String messageKey, UiTokens tokens) {
        return new OperationResult(false, messageKey, tokens == null ? new UiTokens() : tokens);
    }
}

