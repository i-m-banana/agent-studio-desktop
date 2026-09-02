package com.agentstudio.knowledge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class TextChunker {

    private static final int TARGET_SIZE = 900;
    private static final int MAX_SIZE = 1_100;
    private static final int OVERLAP = 120;

    public List<String> chunk(String source) {
        var text = source.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\t ]+", " ").trim();
        var chunks = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(start + MAX_SIZE, text.length());
            int end = hardEnd;
            if (hardEnd < text.length()) {
                int boundary = bestBoundary(text, start + TARGET_SIZE, hardEnd);
                if (boundary > start) end = boundary;
            }
            var chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - OVERLAP);
        }
        return chunks;
    }

    private int bestBoundary(String text, int from, int to) {
        for (int index = to - 1; index >= from; index--) {
            char value = text.charAt(index);
            if (value == '\n' || value == '。' || value == '！' || value == '？' || value == '.' || value == ' ') {
                return index + 1;
            }
        }
        return to;
    }
}

