package com.agentstudio.system;

import java.util.Locale;
import org.springframework.http.HttpStatus;

public final class ListSearch {
    private ListSearch() {}
    public static String normalize(String query) {
        var value = query == null ? "" : query.strip();
        if (value.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "搜索文字最多200个字符");
        return value.toLowerCase(Locale.ROOT);
    }
}
