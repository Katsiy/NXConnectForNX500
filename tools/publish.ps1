# 发布 NXConnectForNX500 到 GitHub
#
# 用法（token 从环境变量读，不要写在命令行里）：
#   $env:GH_TOKEN = 'ghp_xxx'      # 或 github_pat_xxx
#   pwsh tools/publish.ps1
#
# 脚本做四件事：
#   1. 通过 API 创建仓库（已存在则跳过）
#   2. 设置 remote 并推送 main
#   3. 设置仓库描述与 topics
#   4. 打印结果 URL
#
# 需要的 token 权限（fine-grained）：
#   - Repository access: 选中 NXConnectForNX500（或 All repositories）
#   - Permissions: Contents = Read and write；Administration = Read and write（建仓库需要）
#   经典 token 则只需 repo 作用域。

param(
    [string]$Owner = 'Katsiy',
    [string]$Repo  = 'NXConnectForNX500',
    [string]$Description = 'Android app for the Samsung NX500: photo/RAW transfer, liveview, remote shutter and camera-screen mirroring',
    [string[]]$Topics = @('samsung-nx500', 'android', 'kotlin', 'camera-remote', 'reverse-engineering', 'wifi', 'ml-remote'),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$token = $env:GH_TOKEN
if (-not $token) { $token = $env:GITHUB_TOKEN }
if (-not $token) {
    Write-Host '错误：未设置 GH_TOKEN。请先 $env:GH_TOKEN = ''<你的 token>''' -ForegroundColor Red
    exit 1
}

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root '.git'))) {
    Write-Host "错误：$root 不是 git 仓库根目录" -ForegroundColor Red
    exit 1
}

$git = 'C:\Program Files\Git\cmd\git.exe'
if (-not (Test-Path $git)) { $git = 'git' }

$apiBase = 'https://api.github.com'
$headers = @{
    Authorization          = "Bearer $token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent'           = 'NXConnectForNX500-publish'
}

function Invoke-GhApi {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$apiBase$Path"
    $args = @{ Method = $Method; Uri = $uri; Headers = $headers; ErrorAction = 'Stop' }
    if ($Body) {
        $args.Body = ($Body | ConvertTo-Json -Depth 6)
        $args.ContentType = 'application/json'
    }
    return Invoke-RestMethod @args
}

Write-Host '=== 1. 校验 token ===' -ForegroundColor Cyan
try {
    $me = Invoke-GhApi -Method Get -Path '/user'
    Write-Host ("  已认证为: " + $me.login)
} catch {
    Write-Host ("  ❌ token 无效或网络不通: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
if ($me.login -ne $Owner) {
    Write-Host ("  ⚠️ token 属于 " + $me.login + "，但目标是 " + $Owner + " —— 若不是同一账号，推送会失败") -ForegroundColor Yellow
}

Write-Host ''
Write-Host '=== 2. 创建仓库 ===' -ForegroundColor Cyan
$exists = $false
try {
    $r = Invoke-GhApi -Method Get -Path "/repos/$Owner/$Repo"
    if ($r) { $exists = $true; Write-Host ("  仓库已存在: " + $r.html_url) }
} catch { }

if (-not $exists) {
    if ($DryRun) {
        Write-Host ("  [dry-run] 将创建 https://github.com/$Owner/$Repo")
    } else {
        try {
            $created = Invoke-GhApi -Method Post -Path '/user/repos' -Body @{
                name        = $Repo
                description = $Description
                homepage    = ''
                private     = $false
                has_issues  = $true
                has_wiki    = $false
                has_projects = $false
                auto_init   = $false
            }
            Write-Host ("  ✅ 已创建: " + $created.html_url)
        } catch {
            Write-Host ("  ❌ 创建失败: " + $_.Exception.Message) -ForegroundColor Red
            Write-Host '     常见原因：token 缺 Administration 权限，或该名字已被占用。' -ForegroundColor Yellow
            exit 1
        }
    }
}

Write-Host ''
Write-Host '=== 3. 配置 remote 并推送 ===' -ForegroundColor Cyan
Push-Location $root
try {
    $remoteUrl = "https://github.com/$Owner/$Repo.git"
    $existing = & $git remote get-url origin 2>$null
    if ($existing) {
        Write-Host ("  更新 origin: " + $existing + " -> " + $remoteUrl)
        & $git remote set-url origin $remoteUrl
    } else {
        Write-Host ("  添加 origin: " + $remoteUrl)
        & $git remote add origin $remoteUrl
    }

    if ($DryRun) {
        Write-Host '  [dry-run] 将执行 git push -u origin main'
    } else {
        # 用 token 拼进 URL 推送，避免交互式凭据提示
        $pushUrl = "https://x-access-token:$token@github.com/$Owner/$Repo.git"
        Write-Host '  正在推送（凭据不显示）…'
        & $git -c "http.extraheader=" push $pushUrl 'refs/heads/main:refs/heads/main' --force 2>&1 |
            ForEach-Object { Write-Host ("    " + $_) }
        if ($LASTEXITCODE -ne 0) {
            Write-Host '  ❌ 推送失败' -ForegroundColor Red
            exit 1
        }
        Write-Host '  ✅ 推送完成'

        # 让本地的 origin 指向不带 token 的地址
        & $git remote set-url origin $remoteUrl
        & $git branch --set-upstream-to=origin/main main 2>&1 | Out-Null
    }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '=== 4. 设置描述与 topics ===' -ForegroundColor Cyan
if ($DryRun) {
    Write-Host '  [dry-run] 将设置描述与 topics'
} else {
    try {
        Invoke-GhApi -Method Patch -Path "/repos/$Owner/$Repo" -Body @{
            description = $Description
            has_issues  = $true
        } | Out-Null
        Write-Host '  ✅ 描述已设置'
    } catch { Write-Host ("  ⚠️ 描述设置失败: " + $_.Exception.Message) -ForegroundColor Yellow }

    try {
        Invoke-GhApi -Method Put -Path "/repos/$Owner/$Repo/topics" -Body @{ names = $Topics } | Out-Null
        Write-Host ('  ✅ topics: ' + ($Topics -join ', '))
    } catch { Write-Host ("  ⚠️ topics 设置失败: " + $_.Exception.Message) -ForegroundColor Yellow }
}

Write-Host ''
Write-Host '=== 完成 ===' -ForegroundColor Green
Write-Host ("  仓库: https://github.com/$Owner/$Repo")
Write-Host ''
Write-Host '  后续建议（网页上做）:' -ForegroundColor Cyan
Write-Host '    - Releases: 上传 dist/NXConnect-0.15.1.apk'
Write-Host '    - Settings > Features: 勾选 Discussions'
Write-Host '    - 等 CI 跑完，确认 build 徽章变绿'
