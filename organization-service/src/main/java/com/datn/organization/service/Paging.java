package com.datn.organization.service;

import com.datn.organization.domain.Dto.PageView;
import java.util.List;

/** page is 0-based; size defaults to 20 and is capped at 50. */
public record Paging(int page, int size) {
    public static Paging of(Integer page, Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(50, Math.max(1, size));
        return new Paging(p, s);
    }
    public long offset() { return (long) page * size; }
    public <T> PageView<T> page(List<T> items, long total) {
        return new PageView<>(items, page, size, total, (int) Math.ceil(total / (double) size));
    }
}
