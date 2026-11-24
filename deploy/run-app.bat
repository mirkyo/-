@echo off
rem 本地启动应用：MySQL 在 Docker(3306)，Qdrant 本机 exe(6334)，应用 8088
rem 前置：docker compose up -d  +  双击 run-qdrant.bat
cd /d "%~dp0.."
set JAVA_HOME=D:\java\jdk-21.0.12
echo 启动知微（8088）...
"%JAVA_HOME%\bin\java.exe" -Xms128m -Xmx512m -jar "target\zw-agent-platform-0.1.0.jar" --spring.config.additional-location=file:./config-local/ --server.port=8088
