[CmdletBinding()]
param(
  # 跳过数据库角色/库创建（只装依赖）。
  [switch]$SkipDatabaseSetup,
  # 安装前不逐个确认（等价于 winget --accept-package-agreements）。
  [switch]$AcceptAll,
  # PostgreSQL 超级用户 postgres 的密码（不传则交互提示）。
  [string]$PostgresPassword = '',
  # 应用账号 tiku 的密码（不传则交互提示；需与后续 .env 的 POSTGRES_PASSWORD 一致）。
  [string]$TikuPassword = ''
)

$ErrorActionPreference = 'Stop'

function Test-Command([string]$Name) {
  return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Read-Secret([string]$Prompt) {
  $secure = Read-Host -Prompt $Prompt -AsSecureString
  $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

Write-Host '======================================================'
Write-Host ' 题库助手 - Windows 依赖一键安装'
Write-Host ' 使用 winget 安装：JDK21 / PostgreSQL17 / MinIO /'
Write-Host ' Redis / Node.js / VC++ 运行库'
Write-Host '======================================================'

# ---- 0. 前置检查 ----
if (-not (Test-Command winget)) {
  throw '未检测到 winget。请使用 Windows 10 1809+ / Windows 11，或先安装 App Installer。'
}

# ---- 1. 依赖清单（源机已用相同包 ID 验证） ----
# Ids: 任一命中即视为已安装。Node 的 LTS 是 meta 包，实际安装的是具体版本包，故给出别名。
$packages = @(
  @{ Name = 'Eclipse Temurin JDK 21';   Ids = @('EclipseAdoptium.Temurin.21.JDK');              Optional = $false },
  @{ Name = 'Microsoft VC++ 2015+ x64'; Ids = @('Microsoft.VCRedist.2015+.x64');                  Optional = $false },
  @{ Name = 'Node.js';                  Ids = @('OpenJS.NodeJS.LTS','OpenJS.NodeJS.22','OpenJS.NodeJS.24','OpenJS.NodeJS.20','OpenJS.NodeJS.18'); Optional = $false },
  @{ Name = 'PostgreSQL 17';            Ids = @('PostgreSQL.PostgreSQL.17');                      Optional = $false },
  @{ Name = 'MinIO Server';             Ids = @('MinIO.Server');                                  Optional = $false },
  @{ Name = 'Redis (windows fork)';     Ids = @('taizod1024.redis-windows-fork');                 Optional = $false }
)

$commonArgs = @('--accept-source-agreements')
if ($AcceptAll) { $commonArgs += '--accept-package-agreements' }

function Test-PackageInstalled([string]$Id) {
  $out = & winget list --id $Id --exact --accept-source-agreements 2>$null | Out-String
  # 已装时 winget list 会输出一行含包 ID 的记录；避免把退出码当唯一判据。
  return ($out -match [regex]::Escape($Id)) -and ($out -match '\S+\s+\S+\s+\S+\s+\S+\s*$')
}

function Test-AnyInstalled([object[]]$Ids) {
  foreach ($candidate in $Ids) { if (Test-PackageInstalled $candidate) { return $true } }
  return $false
}

foreach ($pkg in $packages) {
  Write-Host ''
  Write-Host "==> $($pkg.Name)  ($($pkg.Ids -join ' / '))"
  if (Test-AnyInstalled $pkg.Ids) {
    Write-Host '    已安装，跳过。'
    continue
  }
  if (-not $AcceptAll) {
    $answer = Read-Host "    未安装。现在安装？[Y/n]"
    if ($answer -match '^(n|N|no)$') {
      if ($pkg.Optional) { Write-Host '    跳过（可选）。'; continue }
      Write-Warning '    跳过必需依赖可能导致系统无法启动。'
      continue
    }
  }
  # --silent --disable-interactivity：避免安装器弹出 UAC/向导导致脚本挂起。
  & winget install --id $pkg.Ids[0] --exact @commonArgs --silent --disable-interactivity
  if ($LASTEXITCODE -ne 0) { Write-Warning "    $($pkg.Name) 安装失败，请手动安装后重试。" }
}

Write-Host ''
Write-Host '---- 安装后自检 ----'
# 仅检测命令可执行性；不调用 java/node -version，避免其向 stderr 输出版本被 PowerShell 误报。
Write-Host "java: $(if (Test-Command java) { (Get-Command java).Source } else { '未在 PATH（请新开 PowerShell 或加入系统 PATH）' })"
Write-Host "node: $(if (Test-Command node) { (Get-Command node).Source } else { '未在 PATH（请新开 PowerShell 或加入系统 PATH）' })"
Write-Host "psql: $(if (Test-Command psql) { (Get-Command psql).Source } else { '未在 PATH（可接受，脚本用全路径）' })"

# ---- 2. PostgreSQL 服务 ----
$pgService = Get-Service -Name 'postgresql-x64-17' -ErrorAction SilentlyContinue
if ($null -eq $pgService) {
  Write-Warning '未检测到 postgresql-x64-17 服务。请确认 PostgreSQL 17 安装完成，或服务名不同。'
} elseif ($pgService.Status -ne 'Running') {
  Write-Host '启动 PostgreSQL 服务...'
  Start-Service $pgService.Name
}

# ---- 3.（可选）创建 tiku 角色与 tikuzhushou 库 ----
if (-not $SkipDatabaseSetup) {
  $psql = 'C:\Program Files\PostgreSQL\17\bin\psql.exe'
  if (-not (Test-Path $psql)) {
    $cmd = Get-Command psql -ErrorAction SilentlyContinue
    if ($cmd) { $psql = $cmd.Source } else { throw '找不到 psql，无法创建数据库。请确认 PostgreSQL 安装路径或使用 -SkipDatabaseSetup。' }
  }
  if ([string]::IsNullOrWhiteSpace($PostgresPassword)) {
    $PostgresPassword = Read-Secret 'PostgreSQL 超级用户(postgres)的密码'
  }
  if ([string]::IsNullOrWhiteSpace($TikuPassword)) {
    $TikuPassword = Read-Secret '应用账号 tiku 的密码(请记下，稍后写入 .env 的 POSTGRES_PASSWORD)'
  }
  if ([string]::IsNullOrWhiteSpace($PostgresPassword) -or [string]::IsNullOrWhiteSpace($TikuPassword)) {
    throw '未提供 postgres/tiku 密码，终止创建数据库。'
  }
  $env:PGPASSWORD = $PostgresPassword
  $roleExists = & $psql -w -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='tiku'" 2>$null
  if ($roleExists -match '1') {
    Write-Host '角色 tiku 已存在，跳过创建。'
  } else {
    & $psql -w -h 127.0.0.1 -U postgres -d postgres -c "CREATE ROLE tiku LOGIN PASSWORD '$TikuPassword';"
    if ($LASTEXITCODE -ne 0) { throw '创建角色 tiku 失败。' }
    Write-Host '角色 tiku 已创建。'
  }
  $dbExists = & $psql -w -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='tikuzhushou'" 2>$null
  if ($dbExists -match '1') {
    Write-Host '数据库 tikuzhushou 已存在，跳过创建。'
  } else {
    & $psql -w -h 127.0.0.1 -U postgres -d postgres -c "CREATE DATABASE tikuzhushou OWNER tiku;"
    if ($LASTEXITCODE -ne 0) { throw '创建数据库 tikuzhushou 失败。' }
    Write-Host '数据库 tikuzhushou 已创建。'
  }
  Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
  Write-Host ''
  Write-Host '数据库准备完成。'
  Write-Host '提示：如需 pgvector 向量检索，请按 docs/pgvector-windows.md 编译安装扩展；'
  Write-Host '不安装时会自动使用 JSON embedding 回退，功能不阻断。'
}

Write-Host ''
Write-Host '======================================================'
Write-Host ' 下一步：'
Write-Host '  1) 按 application/.env.example 创建 .env 并填写密钥'
Write-Host '     (DEEPSEEK_API_KEY / APP_CONFIG_ENCRYPTION_KEY 等)'
Write-Host '  2) 执行  .\scripts\Start-Tiku.ps1  启动全部服务'
Write-Host '  3) 执行  .\scripts\Test-Tiku.ps1  健康检查应全 True'
Write-Host '  4) 浏览器访问 http://127.0.0.1:4173 并用 admin 登录'
Write-Host '======================================================'
