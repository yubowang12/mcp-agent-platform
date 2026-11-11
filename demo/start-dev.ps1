# 一键启动开发环境：从 application-local.yml 取 Key → 注入环境变量 → 起 8001 / 8002 / 8003 / 8090
#
# 用法：
#   1) 把 Key 填进 demo/java-mcp-client/src/main/resources/application-local.yml（已 gitignore）
#   2) .\demo\start-dev.ps1
#
# 说明：
#   - Key 只存在 application-local.yml，不写进受版本控制的 application.yml
#   - Java 用 local profile 读该文件；Python 执行器自己也会读同一个文件
#     （agent.py 取 Key：LLM_API_KEY → DASHSCOPE_API_KEY → application-local.yml）
#   - 脚本仍会把 Key 导出成 DASHSCOPE_API_KEY，作为显式兜底，避免路径变化时取不到
#   - Ctrl+C 结束 Java；三个 Python 服务是独立进程，需要时用任务管理器结束

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$localYml = Join-Path $repoRoot 'demo\java-mcp-client\src\main\resources\application-local.yml'

if (-not (Test-Path $localYml)) {
    throw "未找到 application-local.yml。请先创建它并填入 spring.ai.openai.api-key"
}

$match = Select-String -Path $localYml -Pattern '^\s*api-key:\s*(\S+)' | Select-Object -First 1
if (-not $match) {
    throw "application-local.yml 里没有 spring.ai.openai.api-key"
}
$apiKey = $match.Matches[0].Groups[1].Value.Trim()
if (-not $apiKey -or $apiKey -eq 'sk-xxx') {
    throw "application-local.yml 里的 api-key 还是占位符，请填入真实的 DashScope Key"
}

# Python 执行器从这里取 Key；Java 自己读 local profile
$env:DASHSCOPE_API_KEY = $apiKey
$env:SPRING_PROFILES_ACTIVE = 'local'

$python = Join-Path $repoRoot 'demo\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { throw "找不到虚拟环境：$python" }

Write-Host '[1/4] 启动 demo-tools (8001) ...'
Start-Process -FilePath $python -ArgumentList 'demo\py-mcp-server\server.py' -WorkingDirectory $repoRoot

Write-Host '[2/4] 启动 agent-worker (8002) ...'
Start-Process -FilePath $python -ArgumentList 'demo\py-agent\agent.py' -WorkingDirectory $repoRoot

Start-Sleep -Seconds 3

Write-Host '[3/4] 启动 rag-server (8003) ...'
Start-Process -FilePath $python -ArgumentList 'demo\py-rag-server\server.py' -WorkingDirectory $repoRoot

Start-Sleep -Seconds 3

Write-Host '[4/4] 启动 Java 编排层 (8090，local profile) ...'
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml spring-boot:run

