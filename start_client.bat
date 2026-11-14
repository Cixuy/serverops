@echo off
chcp 65001 >nul
title ServerOps Client

:: ==========================================
:: 请根据实际情况修改下方的两个路径
:: ==========================================
set VENV_DIR=client\.venv
set PROJECT_DIR=client

echo [INFO] 正在激活 Python 虚拟环境...
call "%VENV_DIR%\Scripts\activate.bat" || (
    echo [ERROR] 虚拟环境激活失败，请检查路径: %VENV_DIR%
    pause
    exit /b 1
)

echo [INFO] 虚拟环境已激活: %VIRTUAL_ENV%
echo [INFO] 正在切换到客户端目录: %PROJECT_DIR%
cd /d "%PROJECT_DIR%" || (
    echo [ERROR] 目录不存在，请检查路径配置！
    pause
    exit /b 1
)

echo [INFO] 正在启动 serverops-client...
echo ------------------------------------------
serverops-client

:: 如果客户端异常退出，暂停以便查看错误信息
echo ------------------------------------------
echo [WARN] 客户端已停止。
pause