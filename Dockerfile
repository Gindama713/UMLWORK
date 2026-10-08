FROM eclipse-temurin:21-jre-alpine

ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
