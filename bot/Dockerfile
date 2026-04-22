# bot/Dockerfile
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY Slpa.Bot.sln ./
COPY src/Slpa.Bot/Slpa.Bot.csproj src/Slpa.Bot/
COPY tests/Slpa.Bot.Tests/Slpa.Bot.Tests.csproj tests/Slpa.Bot.Tests/
RUN dotnet restore src/Slpa.Bot/Slpa.Bot.csproj
COPY . .
RUN dotnet publish src/Slpa.Bot/Slpa.Bot.csproj -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:8.0
WORKDIR /app
COPY --from=build /app/publish .
# Critical: .NET 8 defaults to 8080; the healthcheck (+ compose block)
# assume 8081. Do not drop this ENV. See FOOTGUNS entry (Task 12).
ENV ASPNETCORE_HTTP_PORTS=8081
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O- http://localhost:8081/health || exit 1
ENTRYPOINT ["dotnet", "Slpa.Bot.dll"]
