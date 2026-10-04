# Built from trusted toolchain metadata only, never from a business workspace.
FROM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS node
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && mkdir -p /work /opt/maven-cache /opt/npm-cache /opt/seed \
 && chown 1000:1000 /work
COPY pom.xml /opt/seed/pom.xml
RUN mvn -B --no-transfer-progress -f /opt/seed/pom.xml -Dmaven.repo.local=/opt/maven-cache dependency:go-offline
RUN mkdir -p /opt/seed/src/main/java/example /opt/seed/src/test/java/example \
 && printf '%s\n' 'package example; public class Seed { public static void main(String[] args) {} }' > /opt/seed/src/main/java/example/Seed.java \
 && printf '%s\n' 'package example; class SeedTest { @org.junit.jupiter.api.Test void seed() { org.junit.jupiter.api.Assertions.assertTrue(true); } }' > /opt/seed/src/test/java/example/SeedTest.java \
 && mvn -B --no-transfer-progress -f /opt/seed/pom.xml -Dmaven.repo.local=/opt/maven-cache clean test package
COPY spring-boot-3.2.5-pom.xml /opt/seed/boot325/pom.xml
COPY maven-central-settings.xml /opt/seed/maven-central-settings.xml
RUN mvn -s /opt/seed/maven-central-settings.xml -B --no-transfer-progress -f /opt/seed/boot325/pom.xml -Dmaven.repo.local=/opt/maven-cache dependency:go-offline \
 && cp -r /opt/seed/src /opt/seed/boot325/src \
 && mvn -s /opt/seed/maven-central-settings.xml -B --no-transfer-progress -f /opt/seed/boot325/pom.xml -Dmaven.repo.local=/opt/maven-cache clean test package \
 && mvn -o -B --no-transfer-progress -f /opt/seed/boot325/pom.xml -Dmaven.repo.local=/opt/maven-cache clean test package
COPY package.json package-lock.json /opt/seed/web/
RUN printf '%s\n' 'package example; class SeedIT { @org.junit.jupiter.api.Test void seed() { org.junit.jupiter.api.Assertions.assertTrue(true); } }' > /opt/seed/boot325/src/test/java/example/SeedIT.java \
 && mvn -s /opt/seed/maven-central-settings.xml -B --no-transfer-progress -f /opt/seed/boot325/pom.xml -Dmaven.repo.local=/opt/maven-cache test-compile failsafe:integration-test failsafe:verify \
 && mvn -o -B --no-transfer-progress -f /opt/seed/boot325/pom.xml -Dmaven.repo.local=/opt/maven-cache test-compile failsafe:integration-test failsafe:verify
RUN cd /opt/seed/web && npm ci --ignore-scripts --cache=/opt/npm-cache --no-audit --no-fund && rm -rf node_modules
ENV CI=true NO_COLOR=1 NPM_CONFIG_CACHE=/opt/npm-cache
USER 1000:1000
WORKDIR /work
ENTRYPOINT ["/bin/sh"]
