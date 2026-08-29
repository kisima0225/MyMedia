# 按 seed/manifest.json 获取演示媒体。幂等：已存在且校验和相符的条目直接跳过。
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$manifest = Get-Content "$root/seed/manifest.json" -Raw -Encoding utf8 | ConvertFrom-Json
$media = Join-Path $root 'data/media'
$nfo = Join-Path $root 'seed/nfo'
$ua = $manifest.userAgent
$interval = $manifest.minIntervalSeconds

foreach ($a in $manifest.assets) {
    $dest = Join-Path $media $a.target
    if ((Test-Path $dest) -and ((Get-FileHash $dest -Algorithm SHA256).Hash.ToLower() -eq $a.sha256)) {
        Write-Output "跳过（已存在且校验和相符）：$($a.target)"
        continue
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Write-Output "下载：$($a.target)"
    # Windows PowerShell 5.1 的 Invoke-WebRequest 没有 -MaximumRetryCount / -RetryIntervalSec
    # 参数（那是 PowerShell 6+ 才加的），实测会直接报 ParameterBindingException。
    # 自己写重试循环，语义对齐 fetch-seed.sh 的 curl --retry 3 --retry-delay 5。
    $attempt = 0
    while ($true) {
        $attempt++
        try {
            Invoke-WebRequest -Uri $a.url -OutFile "$dest.part" -UserAgent $ua
            break
        } catch {
            if ($attempt -ge 4) { throw }
            Write-Output "  下载失败（第 $attempt 次），5 秒后重试：$($_.Exception.Message)"
            Start-Sleep -Seconds 5
        }
    }
    $actual = (Get-FileHash "$dest.part" -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $a.sha256) {
        Remove-Item "$dest.part" -Force
        throw "校验和不符：$($a.target)`n  期望 $($a.sha256)`n  实际 $actual"
    }
    Move-Item "$dest.part" $dest -Force
    Start-Sleep -Seconds $interval
}

foreach ($ar in $manifest.archives) {
    $dest = Join-Path $media $ar.target
    if (Test-Path $dest) { Write-Output "跳过（已存在）：$($ar.target)"; continue }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $i = 1
    foreach ($id in $ar.pages) {
        $src = Join-Path $media (($manifest.assets | Where-Object { $_.id -eq $id }).target)
        Copy-Item $src (Join-Path $tmp ('{0:d3}.jpg' -f $i))
        $i++
    }
    # Compress-Archive 是 PowerShell 自带的，不需要装 zip
    Compress-Archive -Path (Join-Path $tmp '*') -DestinationPath "$dest.zip" -Force
    Move-Item "$dest.zip" $dest -Force
    Remove-Item $tmp -Recurse -Force
    Write-Output "已打包：$($ar.target)"
}

if (Test-Path $nfo) {
    Copy-Item -Path (Join-Path $nfo '*') -Destination $media -Recurse -Force
    Write-Output "已复制 .nfo"
}

Write-Output ''
Write-Output '完成。媒体目录：'
Get-ChildItem $media -Recurse -File | ForEach-Object { $_.FullName }
