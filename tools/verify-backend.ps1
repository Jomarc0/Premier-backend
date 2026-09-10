param([switch]$Postgres, [string]$Log = 'target/verification.log')
$ErrorActionPreference = 'Stop'
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$mavenExecutable = if ($mavenCommand) { $mavenCommand.Source } else {
    (Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/wrapper/dists') -Recurse -Filter mvn.cmd | Select-Object -First 1).FullName
}
if (!$mavenExecutable) { throw 'Install Maven or initialize the Maven wrapper first.' }
$arguments = @('-q')
if ($Postgres) {
    # Explicit JVM properties override @ActiveProfiles(test); never use a production DB URL here.
    $arguments += @('-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55440/premier_test',
        '-Dspring.datasource.username=remediation', '-Dspring.datasource.password=',
        '-Dspring.datasource.driver-class-name=org.postgresql.Driver',
        '-Dspring.datasource.hikari.data-source-properties.ssl=false',
        '-Dspring.datasource.hikari.data-source-properties.sslmode=disable',
        '-Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect')
}
$arguments += 'test'
$startedAt = Get-Date
& $mavenExecutable @arguments *> $Log
$testExit = $LASTEXITCODE
Get-ChildItem target/surefire-reports -Filter '*.txt' | Where-Object { $_.LastWriteTime -ge $startedAt } | ForEach-Object {
    Select-String -LiteralPath $_.FullName -Pattern '^Tests run:' | ForEach-Object Line
}
if ($Postgres -and !(Select-String -LiteralPath $Log -SimpleMatch 'org.postgresql.jdbc.PgConnection' -Quiet)) {
    throw 'PostgreSQL connection evidence missing; do not count this as a PostgreSQL run.'
}
Write-Output "MAVEN_EXIT=$testExit"
exit $testExit
