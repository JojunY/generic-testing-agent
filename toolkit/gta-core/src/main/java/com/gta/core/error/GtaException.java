package com.gta.core.error;

import java.util.LinkedHashMap;
import java.util.Map;

/** The single exception type of gta. It always carries a stable code and an actionable hint. */
public class GtaException extends RuntimeException {
    private final ErrorCode code;
    private final String hint;
    private final Map<String, Object> details = new LinkedHashMap<>();

    public GtaException(ErrorCode code, String message, String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }

    public GtaException(ErrorCode code, String message, String hint, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.hint = hint;
    }

    public GtaException detail(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public String hint() {
        return hint;
    }

    public Map<String, Object> details() {
        return details;
    }

    /** The unified error object of plan §5.6. */
    public Map<String, Object> toErrorObject() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("category", code.category.id);
        error.put("message", getMessage());
        error.put("hint", hint);
        error.put("retryable", code.retryable());
        if (!details.isEmpty()) {
            error.put("details", details);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", error);
        return root;
    }
}
