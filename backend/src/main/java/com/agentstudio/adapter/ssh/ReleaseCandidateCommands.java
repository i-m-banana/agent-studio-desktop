package com.agentstudio.adapter.ssh;

final class ReleaseCandidateCommands {
    String verify(String candidatePath, String releaseId, String artifactSha256) {
        return """
                set -eu
                candidate=%s
                test ! -L "$candidate"
                test -d "$candidate"
                cd "$candidate"
                for file in app.jar Dockerfile compose.yml nginx.conf manifest.properties SHA256SUMS; do
                  test ! -L "$file"
                  test -f "$file"
                done
                sha256sum -c SHA256SUMS > /dev/null
                grep -Fx %s manifest.properties > /dev/null
                grep -Fx %s manifest.properties > /dev/null
                artifact_bytes="$(stat -c %%s -- app.jar)"
                manifest_sha="$(sha256sum manifest.properties | cut -d' ' -f1)"
                printf 'RELEASE_ID=%%s\nCANDIDATE_PATH=%%s\nARTIFACT_SHA256=%%s\nARTIFACT_BYTES=%%s\nMANIFEST_SHA256=%%s\nFILE_COUNT=6\n' \
                  %s "$candidate" %s "$artifact_bytes" "$manifest_sha"
                """.formatted(quote(candidatePath), quote("releaseId=" + releaseId),
                quote("artifactSha256=" + artifactSha256), quote(releaseId), quote(artifactSha256));
    }

    private static String quote(String value) {
        if (value.indexOf('\'') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("候选发布配置包含不安全字符");
        }
        return "'" + value + "'";
    }
}
