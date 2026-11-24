# Build stage: compile and run the FULL test suite. A failing test fails the image build,
# so nothing untested can ever be deployed.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x run.sh test.sh && ./test.sh

# Runtime stage: only the compiled classes on a small JRE image.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/classes ./classes
ENV MINIDB_DATA_DIR=/data \
    MINIDB_DB=minidb \
    MINIDB_PORT=6969 \
    MINIDB_CACHE_PAGES=100
EXPOSE 6969
# Data lives in /data: mount a persistent volume there.
CMD ["java", "-cp", "classes", "com.minidb.Main", "server"]
