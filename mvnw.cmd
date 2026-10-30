@echo off
setlocal
set "DIR=%~dp0"
if exist "%DIR%tools\apache-maven-3.9.9\bin\mvn.cmd" (
    call "%DIR%tools\apache-maven-3.9.9\bin\mvn.cmd" %*
) else (
    mvn %*
)

