<#
  Arranca notificaciones-service en local con las credenciales Gmail SMTP
  cargadas desde .env.local (gitignored, nunca se commitea).

  Uso:
    .\start-local.ps1             # arranca en esta terminal (Ctrl+C para parar)
    .\start-local.ps1 -Detached   # arranca desacoplado (log en run.log / run-err.log)

  Requisitos: .env.local con MAIL_HOST, MAIL_PORT, MAIL_USER, MAIL_PASS, MAIL_FROM.
  (La contrasena es una "contrasena de aplicacion" de Google: myaccount.google.com/apppasswords)
#>
param([switch]$Detached)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$envFile = Join-Path $root ".env.local"

if (-not (Test-Path $envFile)) {
  Write-Error "No existe .env.local. Crealo con MAIL_HOST, MAIL_PORT, MAIL_USER, MAIL_PASS, MAIL_FROM."
  exit 1
}

# Carga .env.local en variables de entorno del PROCESO actual
Get-Content $envFile | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith("#")) {
    $idx = $line.IndexOf("=")
    if ($idx -gt 0) {
      $name  = $line.Substring(0, $idx).Trim()
      $value = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
      Set-Item -Path "Env:$name" -Value $value
    }
  }
}

# Verifica minimas + que la contrasena no quede como placeholder
$faltan = @("MAIL_HOST","MAIL_USER","MAIL_PASS","MAIL_FROM") | Where-Object { -not (Get-Item "Env:$_" -ErrorAction SilentlyContinue) }
if ($faltan) { Write-Error "Faltan en .env.local: $($faltan -join ', ')"; exit 1 }
if ($env:MAIL_PASS -like "PEGA_AQUI*") {
  Write-Error "Todavia no pusiste la contrasena real en .env.local (MAIL_PASS)."
  exit 1
}

Write-Host "Cargado: MAIL_HOST=$env:MAIL_HOST | MAIL_USER=$env:MAIL_USER | MAIL_PASS=($($env:MAIL_PASS.Length) chars) | MAIL_FROM=$env:MAIL_FROM"

if ($Detached) {
  # El hijo (cmd -> mvnw -> java) hereda las variables Env: del proceso actual
  Start-Process cmd -ArgumentList "/c", ".\mvnw.cmd spring-boot:run > run.log 2> run-err.log" -WorkingDirectory $root -WindowStyle Hidden
  Write-Host "Arrancado desacoplado. Logs: run.log / run-err.log"
} else {
  & (Join-Path $root "mvnw.cmd") spring-boot:run
}
