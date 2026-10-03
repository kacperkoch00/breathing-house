package com.breathinghouse.homeapi.history;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int limit,
        int offset,
        boolean hasMore
) {
}
