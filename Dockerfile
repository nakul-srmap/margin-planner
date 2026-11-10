# Margin: build with the JDK, run on a slim JRE.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN java Build.java build

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/classes build/classes
COPY lib lib
# Plan in Indian time; keep memory inside a 512 MB free instance.
ENV TZ=Asia/Kolkata \
    JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
CMD ["java", "-cp", "build/classes:lib/*", "planner.App"]
