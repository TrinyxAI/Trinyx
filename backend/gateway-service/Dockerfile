# syntax=docker/dockerfile:1.7-labs
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${MAVEN_IMAGE} AS build
WORKDIR /app
COPY pom.xml ./
COPY --parents */pom.xml ./
RUN mvn -B -DskipTests -pl gateway-service -am dependency:go-offline
COPY . .
RUN mvn -B -DskipTests -pl gateway-service -am clean package \
    && mkdir -p /tmp/gateway-jar-check \
    && cd /tmp/gateway-jar-check \
    && jar --extract --file /app/gateway-service/target/app.jar META-INF/MANIFEST.MF \
    && grep -Fq 'Main-Class: org.springframework.boot.loader.launch.JarLauncher' META-INF/MANIFEST.MF \
    && grep -Fq 'Start-Class: com.apimarketplace.gateway.TrinyxGatewayApplication' META-INF/MANIFEST.MF \
    && rm -rf /tmp/gateway-jar-check

FROM ${JRE_IMAGE}
RUN apk add --no-cache curl
RUN addgroup -g 1001 -S appgroup && adduser -u 1001 -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/gateway-service/target/app.jar /app/app.jar
USER appuser
EXPOSE 8086
ENTRYPOINT ["java","-jar","/app/app.jar"]
