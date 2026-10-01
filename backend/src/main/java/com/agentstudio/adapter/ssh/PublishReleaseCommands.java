package com.agentstudio.adapter.ssh;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class PublishReleaseCommands {
    static String compose(RemoteDeploymentProfile p) {
        return "docker compose --project-name " + q(p.composeProject()) + " --file " + q(p.composeFile());
    }
    static String fingerprint(RemoteDeploymentProfile p) {
        return "for file in app.jar Dockerfile " + q(p.composeFile()) + " " + q(p.nginxConfig())
                + " .env; do test ! -L \"$file\" && test -s \"$file\" || exit 42; sha256sum \"$file\" | cut -d' ' -f1; done | sha256sum | cut -d' ' -f1";
    }
    static String status(RemoteDeploymentProfile p) {
        return "set -eu; unset DOCKER_CONTEXT; export DOCKER_HOST=unix:///var/run/docker.sock; cd " + q(p.remoteDeployRoot())
                + "; cid=$(timeout -k 2s 10s " + compose(p) + " ps -q app); test -n \"$cid\"; "
                + "printf 'CURRENT_IMAGE_ID='; timeout -k 2s 10s docker inspect --format '{{.Image}}' \"$cid\"; "
                + "printf 'APP_HEALTH='; timeout -k 2s 10s docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' \"$cid\"; "
                + "printf 'PRODUCTION_SHA256='; " + fingerprint(p);
    }
    String script(RemoteDeploymentProfile p, String candidate, String attempt, String token, Map<String,String> args, Map<String,String> hashes) throws Exception {
        String template;
        try (var input = getClass().getResourceAsStream("/ssh/publish-release.sh")) {
            template = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        var values = new LinkedHashMap<String,String>();
        values.put("CANDIDATE", q(candidate)); values.put("ATTEMPT", q(attempt));
        values.put("BACKUP", q(p.remoteBackupRoot() + "/" + args.get("backupId"))); values.put("DEPLOY", q(p.remoteDeployRoot()));
        values.put("IMAGE", q(args.get("imageId"))); values.put("PREVIOUS", q(args.get("previousImageId")));
        values.put("CONTAINER", q("agentstudio-publish-" + token)); values.put("TAG", q(p.composeProject() + "-app"));
        values.put("COMPOSE", compose(p)); values.put("COMPOSE_FILE", q(p.composeFile())); values.put("NGINX_FILE", q(p.nginxConfig()));
        values.put("HEALTH", q(p.healthUrl()));
        var uri = java.net.URI.create(p.healthUrl());
        values.put("BUSINESS", q(new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/browse", null, null).toString()));
        values.put("NETWORK", q(p.composeProject() + "_internal")); values.put("TOKEN", token);
        values.put("FINGERPRINT", q(args.get("productionSha256"))); values.put("MANIFEST", q(args.get("manifestSha256")));
        values.put("BACKUP_SHA", q(args.get("backupManifestSha256"))); values.put("RELEASE", q(args.get("releaseId")));
        values.put("SCHEMA", q(args.get("schemaSha256")));
        values.put("ARTIFACT", q(hashes.get("artifactSha256"))); values.put("DOCKERFILE", q(hashes.get("dockerfileSha256")));
        values.put("COMPOSE_SHA", q(hashes.get("composeSha256"))); values.put("NGINX_SHA", q(hashes.get("nginxSha256")));
        for (var value : values.entrySet()) template = template.replace("@" + value.getKey() + "@", value.getValue());
        if (template.matches("(?s).*@[A-Z_]+@.*")) throw new IllegalStateException("发布模板未完整绑定");
        return template;
    }
    static String q(String value) { return DatabaseBaselineCommands.q(value); }
}
