FROM openjdk:17-alpine
WORKDIR /app
COPY . .
RUN mkdir -p /app/applications && mkdir -p /app/logs
CMD [ "java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-Dfile.encoding=UTF8", "-Xms512m", "-Xmx1024m", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true", "--illegal-access=warn", "-jar", "lib/sync-startup-0.3.4.jar" ]