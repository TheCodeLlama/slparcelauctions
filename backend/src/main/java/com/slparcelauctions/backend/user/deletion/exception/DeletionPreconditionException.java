package com.slparcelauctions.backend.user.deletion.exception;

import java.util.List;

public interface DeletionPreconditionException {
    String getCode();
    List<Long> getBlockingIds();
}
