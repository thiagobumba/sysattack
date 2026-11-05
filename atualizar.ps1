# Atualiza o SysAttack na sua máquina: traz a branch do GitHub, apaga a pasta target e gera o .war novo.
# Uso (PowerShell, na pasta do repositório):   .\atualizar.ps1 claude/config-email
# Sem parâmetro, usa a branch main.
param([string]$Branch = "main")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

git fetch origin
git checkout $Branch
git pull origin $Branch
git log --oneline -3

$app = Join-Path $PSScriptRoot "sysattack"
if (-not (Test-Path (Join-Path $app "pom.xml"))) { throw "pom.xml não encontrado em $app" }
Set-Location $app

if (Test-Path "target") { Remove-Item -Recurse -Force "target" }
mvn clean package -Pjakarta

Write-Host ""
Write-Host "Pronto: $app\target\sysattack.war"
Write-Host "Agora: troque o .war em webapps do Tomcat, apague a pasta antiga extraída, reinicie o Tomcat e use Ctrl+Shift+R."
