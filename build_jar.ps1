# MARS Pipeline 高兼容性打包脚本 (Target: Java 8)

$JarName = "Mars_pipeline.jar"
$Manifest = "mainclass.txt"
$BuildDir = "temp_build"
$SrcDir = "." # 源码根目录

Write-Host "--- 开始构建兼容性版本 (Target: Java 8) ---" -ForegroundColor Cyan

# 1. 创建干净的临时构建目录
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Path $BuildDir | Out-Null

# 2. 收集所有 Java 源码文件
$JavaFiles = Get-ChildItem -Path $SrcDir -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
$JavaFilesCount = $JavaFiles.Count
Write-Host "发现 $JavaFilesCount 个源码文件，正在编译..."

# 3. 编译源码 (关键点：使用 --release 8 保证兼容性，-encoding 处理旧代码中的特殊字符)
# 注意：排除 out 目录防止干扰
$Sources = $JavaFiles | Where-Object { $_ -notmatch "\\out\\" }
javac --release 8 -encoding ISO-8859-1 -d $BuildDir $Sources

if ($LASTEXITCODE -ne 0) {
    Write-Error "编译失败，请检查语法错误。"
    exit 1
}

# 4. 拷贝资源文件 (XML, Properties, txt, images, help)
Write-Host "正在同步资源文件..."
$ResourceExtensions = @("*.txt", "*.properties", "*.xml", "*.png", "*.gif", "*.jpg")
foreach ($ext in $ResourceExtensions) {
    Get-ChildItem -Path $SrcDir -Recurse -Include $ext | Where-Object { $_.FullName -notmatch "\\out\\" -and $_.FullName -notmatch "\\$BuildDir\\" } | ForEach-Object {
        $RelativePath = $_.FullName.Replace((Get-Item $SrcDir).FullName + "\", "")
        $DestPath = Join-Path $BuildDir $RelativePath
        $DestDir = Split-Path $DestPath
        if (-not (Test-Path $DestDir)) { New-Item -ItemType Directory -Path $DestDir | Out-Null }
        Copy-Item $_.FullName -Destination $DestPath -Force
    }
}

# 5. 特殊处理：拷贝 images 和 help 整个目录 (防止遗漏)
if (Test-Path "images") { Copy-Item -Recurse "images" -Destination "$BuildDir/" -ErrorAction SilentlyContinue }
if (Test-Path "help") { Copy-Item -Recurse "help" -Destination "$BuildDir/" -ErrorAction SilentlyContinue }

# 6. 打包 JAR
Write-Host "正在生成 $JarName..."
Push-Location $BuildDir
jar cmf ../$Manifest ../$JarName .
Pop-Location

# 7. 清理
Remove-Item -Recurse -Force $BuildDir

Write-Host "`n打包成功！" -ForegroundColor Green
Write-Host "生成的 Mars.jar 已完美兼容 Java 8 及以上版本，学生可直接双击运行。"
