package com.slparcelauctions.backend.sl.exception;

/** External SL API (World, Map) timed out or repeatedly failed. Maps to HTTP 504. */
public class ExternalApiTimeoutException extends RuntimeException {

    private final String api;

    public ExternalApiTimeoutException(String api, String detail) {
        super("SL " + api + " API unavailable: " + detail);
        this.api = api;
    }

    public String getApi() {
        return api;
    }
}
