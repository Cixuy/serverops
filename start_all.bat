@echo off
chcp 65001 >nul
title Dev Environment Launcher

:: ==========================================
:: 请修改下方路径，使其指向前面三个脚本的实际位置
:: ==========================================
set SCRIPT_DIR=.\

echo ============================================
echo   正在启动开发环境（共 3 个服务）
echo ============================================
echo.

echo [1/3] 启动前端 (npm run dev)...
start "Frontend-Dev" cmd /k "cd /d "%SCRIPT_DIR%" && call start_frontend.bat"

echo [2/3] 启动客户端 (serverops-client)...
start "ServerOps-Client" cmd /k "cd /d "%SCRIPT_DIR%" && call start_client.bat"

echo [3/3] 启动后端 (Spring Boot)...
start "SpringBoot-Backend" cmd /k "cd /d "%SCRIPT_DIR%" && call start_backend.bat"

echo.
echo ============================================
echo   ✅ 所有服务启动指令已发送
echo   请在弹出的独立窗口中查看各服务状态
echo ============================================
echo.
echo 按任意键关闭此启动器窗口（不影响已启动的服务）...
pause >nul