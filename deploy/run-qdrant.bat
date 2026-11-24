@echo off
rem 本地向量库：Qdrant 单文件 exe（与服务器同一套实现）
rem 绑定 127.0.0.1，不对外暴露；存储路径显式指定，避免 CWD 不同导致数据"丢失"
cd /d "%~dp0_pkg\qdrant"
set QDRANT__SERVICE__HOST=127.0.0.1
set QDRANT__SERVICE__HTTP_PORT=6333
set QDRANT__SERVICE__GRPC_PORT=6334
set QDRANT__SERVICE__API_KEY=zw-qd-7Kx9mTp4vQ2nLr
set QDRANT__STORAGE__STORAGE_PATH=%~dp0_pkg\qdrant\storage
set QDRANT__TELEMETRY_DISABLED=true
echo Qdrant 启动中... 6333(REST) / 6334(gRPC)
qdrant.exe --disable-telemetry
