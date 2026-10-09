# Exception Kullanım Tarayıcı - OpenShift imajı (REST API + Swagger)
#
# Şirket içi registry kullanılıyorsa imajlar build argümanıyla değiştirilebilir:
#   docker build --build-arg BUILD_IMAGE=registry.sirket/maven:3.9-eclipse-temurin-17 \
#                --build-arg RUNTIME_IMAGE=registry.sirket/ubi9/openjdk-17-runtime:1.20 .
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi9/openjdk-17-runtime:1.20

FROM ${BUILD_IMAGE} AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM ${RUNTIME_IMAGE}
USER 0
# Bitbucket / git adreslerinden klonlamak için git (ssh protokolü için openssh-clients)
RUN microdnf install -y git-core openssh-clients && microdnf clean all
WORKDIR /app
COPY --from=build /src/target/exception-scanner-1.0.0.jar /app/app.jar
# Varsayılan ayar dosyası; OpenShift'te ConfigMap ile /app/config/scanner.properties üzerine bağlanır
COPY openshift/scanner.properties /app/config/scanner.properties
COPY openshift/entrypoint.sh /app/entrypoint.sh
# OpenShift konteyneri rastgele bir kullanıcıyla ve root grubuyla (0) çalıştırır:
# yazılacak klasörler root grubuna yazılabilir olmalı
RUN chmod 755 /app/entrypoint.sh && mkdir -p /data/work /data/reports \
    && chgrp -R 0 /app /data && chmod -R g=u /app /data
USER 185

ENV HOME=/tmp \
    PORT=8080 \
    SCANNER_CONFIG=/app/config/scanner.properties \
    SCANNER_WORK_DIR=/data/work \
    SCANNER_OUTPUT_DIR=/data/reports \
    JAVA_OPTS_APPEND="" \
    MAX_RAM_PERCENTAGE=75
EXPOSE 8080
# Heap konteyner bellek limitinden hesaplanır (varsayılan %75). Şirket CA'sı için EXTRA_CA_BUNDLE.
ENTRYPOINT ["/app/entrypoint.sh"]
