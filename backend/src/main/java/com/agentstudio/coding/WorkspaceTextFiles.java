package com.agentstudio.coding;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class WorkspaceTextFiles {
    static final long MAX_FILE_BYTES = 1_048_576;

    private WorkspaceTextFiles() {
    }

    static String decodeUtf8(byte[] bytes) {
        for (var value : bytes) {
            if (value == 0) throw new IllegalArgumentException("文件包含二进制 NUL 字节，不能按文本处理");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("文件不是有效的 UTF-8 文本", exception);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
