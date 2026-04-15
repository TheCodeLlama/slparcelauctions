package com.slparcelauctions.backend.storage;

public record StoredObject(byte[] bytes, String contentType, long contentLength) {}
