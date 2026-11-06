<#
.SYNOPSIS
  Restaura um backup do SysAttack (banco, documentos, ai.key e config.properties).

.DESCRIPTION
  Rode com o Tomcat PARADO. O script:
    1. confere o SHA-256 do backup (se houver o arquivo .sha256 ao lado);
    2. confere que o banco não está em uso;
    3. guarda o que existe hoje em "<dados>.antes-da-restauracao-<data>" (nada é apagado);
    4. restaura o banco, os documentos e os arquivos de configuração.

.EXAMPLE
  .\restaurar-backup.ps1 -Backup D:\backups\sysattack\sysattack-20261106-020000-auto.zip -Dados C:\dados\sysattack
#>
param(
  [Parameter(Mandatory = $true)][string]$Backup,
  [string]$Dados = $env:SYSATTACK_DATA,
  [switch]$Sim   # não pergunta antes de restaurar
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $Dados) { $Dados = Join-Path $env:USERPROFILE '.sysattack' }
$Backup = (Resolve-Path $Backup).Path
Write-Host "Backup : $Backup"
Write-Host "Destino: $Dados"

# 1) integridade
$sha = "$Backup.sha256"
if (Test-Path $sha) {
  $esperado = ((Get-Content $sha -Raw).Trim() -split '\s+')[0].ToLower()
  $real = (Get-FileHash $Backup -Algorithm SHA256).Hash.ToLower()
  if ($esperado -ne $real) { throw "SHA-256 não confere: o backup está corrompido ou foi alterado. Use outro." }
  Write-Host 'SHA-256 confere.'
} else { Write-Warning 'Sem arquivo .sha256 ao lado do backup: a integridade só será checada pelo zip.' }

$zip = [System.IO.Compression.ZipFile]::OpenRead($Backup)
try {
  $manifesto = $zip.Entries | Where-Object FullName -eq 'manifest.json'
  $fisico    = $zip.Entries | Where-Object FullName -eq 'database/physical.zip'
  if (-not $manifesto -or -not $fisico) { throw 'Arquivo não parece um backup do SysAttack (faltam manifest.json ou database/physical.zip).' }
  $r = New-Object System.IO.StreamReader($manifesto.Open())
  $info = $r.ReadToEnd() | ConvertFrom-Json; $r.Close()
  Write-Host ("Backup de {0} (tipo {1}, H2 {2})." -f $info.created_at, $info.kind, $info.h2_version)

  # 2) banco em uso?
  $mv = Join-Path $Dados 'sysattack.mv.db'
  if (Test-Path $mv) {
    try { $f = [System.IO.File]::Open($mv, 'Open', 'ReadWrite', 'None'); $f.Close() }
    catch { throw 'O banco está em uso. Pare o Tomcat antes de restaurar.' }
  }
  if (-not $Sim) {
    $ok = Read-Host 'Restaurar este backup? O conteúdo atual será guardado ao lado. (S/N)'
    if ($ok -notmatch '^[sSyY]') { Write-Host 'Cancelado.'; return }
  }

  # 3) guarda o estado atual
  $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
  if (Test-Path $Dados) {
    $guard = "$Dados.antes-da-restauracao-$ts"
    New-Item -ItemType Directory -Path $guard | Out-Null
    foreach ($n in 'sysattack.mv.db', 'sysattack.trace.db', 'docs', 'ai.key', 'config.properties', 'schema.version') {
      $p = Join-Path $Dados $n
      if (Test-Path $p) { Move-Item $p $guard }
    }
    Write-Host "Estado anterior guardado em: $guard"
  } else { New-Item -ItemType Directory -Path $Dados | Out-Null }

  # 4) restaura
  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) "sysattack-restore-$ts"
  New-Item -ItemType Directory -Path $tmp | Out-Null
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($fisico, (Join-Path $tmp 'physical.zip'))
  $pz = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $tmp 'physical.zip'))
  try {
    $e = $pz.Entries | Where-Object { $_.FullName -like '*.mv.db' } | Select-Object -First 1
    if (-not $e) { throw 'A cópia física não contém o arquivo do banco.' }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $mv, $true)
  } finally { $pz.Dispose() }
  Remove-Item $tmp -Recurse -Force

  $docs = Join-Path $Dados 'docs'
  New-Item -ItemType Directory -Path $docs -Force | Out-Null
  foreach ($e in ($zip.Entries | Where-Object { $_.FullName -like 'files/docs/*' -and $_.Name })) {
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $docs $e.Name), $true)
  }
  foreach ($e in ($zip.Entries | Where-Object { $_.FullName -like 'config/*' -and $_.Name })) {
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $Dados $e.Name), $true)
  }
} finally { $zip.Dispose() }

Write-Host ''
Write-Host 'Restauração concluída. Inicie o Tomcat e confira o login e os projetos.' -ForegroundColor Green
Write-Host 'Se algo estiver errado, o estado anterior continua na pasta ".antes-da-restauracao-*".'
