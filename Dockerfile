# Duas etapas: a primeira compila com JDK e Maven; a imagem final leva so a JRE e o jar.

FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace

# Sem unzip, o Maven Wrapper baixa o .tar.gz do Maven, e o checksum configurado (do .zip) nao confere.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*

# Dependencias primeiro: enquanto o pom nao mudar, essa camada vem do cache do Docker.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline

COPY src src
RUN ./mvnw -B -ntp -q package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app

# Sem root dentro do container; UID numerico para orquestradores conseguirem verificar isso.
RUN groupadd --system --gid 10001 wallet && useradd --system --uid 10001 --gid wallet --no-create-home wallet

COPY --from=build /workspace/target/wallet-api-*.jar app.jar
USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# Heap em ate 75% da memoria do container, em vez de um -Xmx fixo.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
