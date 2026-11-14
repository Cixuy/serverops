@echo off
chcp 65001 >nul
title Frontend Dev Server

:: ==========================================
:: 请根据实际情况修改下方的项目路径
:: ==========================================
set PROJECT_DIR=frontend

echo [INFO] 正在切换到前端目录: %PROJECT_DIR%
cd /d "%PROJECT_DIR%" || (
    echo [ERROR] 目录不存在，请检查路径配置！
    pause
    exit /b 1
)

echo [INFO] 正在启动前端开发服务器 (npm run dev)...
echo ------------------------------------------
call npm run dev

:: 如果 npm 进程异常退出，暂停以便查看错误信息
echo ------------------------------------------
echo [WARN] 前端服务已停止。
pause