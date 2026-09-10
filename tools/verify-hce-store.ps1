$ErrorActionPreference='Stop'
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Jar([string]$group,[string]$artifact) {
    $item=Get-ChildItem -LiteralPath (Join-Path $cache "$group/$artifact") -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if(!$item){throw "Missing cached compiler dependency: $artifact"}
    return $item.FullName
}
$jars=@((Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable'),(Jar 'org.jetbrains.kotlin' 'kotlin-stdlib'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-reflect'),(Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable'),(Jar 'org.jetbrains.intellij.deps' 'trove4j'),
    (Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm'),(Jar 'org.jetbrains' 'annotations'),
    (Jar 'junit' 'junit'),(Jar 'org.hamcrest' 'hamcrest-core'))
$classpath=$jars -join [IO.Path]::PathSeparator
$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'target/hce-jvm-tests'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$source='C:/Users/Jomar/Premier/users-mobile/android/app/src'
$java='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin/java.exe'
& $java -cp $classpath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $classes `
    "$source/main/java/com/togi012322/premier/PremierHceTokenStore.kt" `
    "$source/test/java/com/togi012322/premier/PremierHceTokenStoreTest.kt"
if($LASTEXITCODE -ne 0){throw 'Kotlin HCE state compilation failed.'}
& $java -cp ($classes+[IO.Path]::PathSeparator+$classpath) org.junit.runner.JUnitCore com.togi012322.premier.PremierHceTokenStoreTest
if($LASTEXITCODE -ne 0){throw 'HCE lifecycle state tests failed.'}
