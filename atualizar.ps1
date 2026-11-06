# Atualiza o SysAttack na sua máquina: traz a branch do GitHub, apaga a pasta target e gera o .war novo.
#
# Uso (PowerShell, na pasta do repositório):
#   .\atualizar.ps1 claude/relatorios          atualiza uma vez
#   .\atualizar.ps1 claude/relatorios -Watch   fica vigiando o GitHub e atualiza sozinho a cada novo commit
#                                              (deixe a janela aberta; Ctrl+C para parar)
# Sem parâmetro, usa a branch claude/dev (onde o Claude sobe as alterações).
param([string]$Branch = "claude/dev", [switch]$Watch, [int]$IntervaloSegundos = 60)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Atualizar {
    git fetch origin
    if ($LASTEXITCODE -ne 0) { throw "git fetch falhou." }
    git checkout $Branch
    if ($LASTEXITCODE -ne 0) { throw "git checkout $Branch falhou." }
    git pull origin $Branch
    if ($LASTEXITCODE -ne 0) {
        throw "git pull falhou (provavelmente alterações locais em arquivos do projeto). NÃO vou gerar o .war com código antigo. Veja 'git status'; para descartar as alterações locais e igualar ao GitHub: git reset --hard origin/$Branch"
    }
    $local = (git rev-parse HEAD).Trim(); $remoto = (git rev-parse "origin/$Branch").Trim()
    if ($local -ne $remoto) { throw "O código local ($($local.Substring(0,8))) não está igual ao GitHub ($($remoto.Substring(0,8))). Resolva com: git reset --hard origin/$Branch" }
    git log --oneline -3

    $app = Join-Path $PSScriptRoot "sysattack"
    if (-not (Test-Path (Join-Path $app "pom.xml"))) { throw "pom.xml não encontrado em $app" }
    Push-Location $app
    try {
        if (Test-Path "target") { Remove-Item -Recurse -Force "target" }
        mvn clean package -Pjakarta
        if ($LASTEXITCODE -ne 0) { throw "O build do Maven falhou." }
    } finally { Pop-Location }

    Write-Host ""
    Write-Host "Pronto: $app\target\sysattack.war" -ForegroundColor Green
    Write-Host "Agora: troque o .war em webapps do Tomcat, apague a pasta antiga extraída, reinicie o Tomcat e use Ctrl+Shift+R."
}

Atualizar
if (-not $Watch) { return }

Write-Host ""
Write-Host "Modo vigia: verificando '$Branch' a cada $IntervaloSegundos s. Ctrl+C para parar." -ForegroundColor Cyan
$ultimo = (git rev-parse HEAD).Trim()
while ($true) {
    Start-Sleep -Seconds $IntervaloSegundos
    try {
        git fetch origin --quiet
        $remoto = (git rev-parse "origin/$Branch").Trim()
        if ($remoto -ne $ultimo) {
            Write-Host "[$(Get-Date -Format HH:mm:ss)] Novo commit em $Branch. Atualizando..." -ForegroundColor Yellow
            Atualizar
            $ultimo = (git rev-parse HEAD).Trim()
        }
    } catch {
        Write-Host "[$(Get-Date -Format HH:mm:ss)] Falha: $($_.Exception.Message)" -ForegroundColor Red
    }
}
