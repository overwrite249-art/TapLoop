package org.json;

// Test-only stand-in for Android's org.json, see compileJsonShim in build.gradle.
public class JSONException extends Exception {
    public JSONException(String s) {
        super(s);
    }

    public JSONException(String s, Throwable cause) {
        super(s, cause);
    }

    public JSONException(Throwable cause) {
        super(cause);
    }
}
