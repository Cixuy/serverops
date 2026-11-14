@echo off
chcp 65001 >nul
title SpringBoot Backend (Dev)

:: ==========================================
:: 请根据实际情况修改下方的路径和构建工具
:: ==========================================
set PROJECT_DIR=backend
:: 构建工具: maven 或 gradle
set BUILD_TOOL=maven

echo [INFO] 正在切换到后端目录: %PROJECT_DIR%
cd /d "%PROJECT_DIR%" || (
    echo [ERROR] 目录不存在，请检查路径配置！
    pause
    exit /b 1
)

if "%BUILD_TOOL%"=="maven" (
    echo [INFO] 使用 Maven 启动 Spring Boot...
    echo ------------------------------------------
    call mvnw.cmd spring-boot:run
    if errorlevel 1 call mvn spring-boot:run
) else if "%BUILD_TOOL%"=="gradle" (
    echo [INFO] 使用 Gradle 启动 Spring Boot...
    echo ------------------------------------------
    call gradlew.bat bootRun
    if errorlevel 1 call gradle bootRun
) else (
    echo [ERROR] 未知的构建工具: %BUILD_TOOL%，请设置为 maven 或 gradle
    pause
    exit /b 1
)

echo ------------------------------------------
echo [WARN] 后端服务已停止。
pause