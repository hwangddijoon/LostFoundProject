# 1단계: Maven으로 빌드하고 단위 테스트 실행 (테스트가 실패하면 이미지가 만들어지지 않음)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

# 2단계: 실행에 필요한 JRE와 jar만
FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build /src/target/lostfound.jar app.jar
USER appuser
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
