# Zero-Leak Policy Verification Script
# This script scans the Vusense Android SDK source code for potential information leaks

param(
    [string]$SourcePath = "sdk\src\main\java",
    [switch]$Verbose
)

Write-Host "Starting Zero-Leak Policy Verification..." -ForegroundColor Green
Write-Host "Scanning path: $SourcePath" -ForegroundColor Yellow

# Patterns that could indicate sensitive data leaks
$SensitivePatterns = @{
    # Hardcoded secrets
    "HardcodedKeys" = "(private[_\s]*key|secret[_\s]*key|api[_\s]*key|password|token)\s*=\s*`"[^`"]+`""
    "HardcodedPasswords" = "password\s*=\s*`"[^`"]+`""
    "HardcodedTokens" = "(token|secret)\s*=\s*`"[^`"]+`""
    
    # Sensitive data in logs
    "LogKeys" = "Log\.[dewi]\s*\([^)]*\b(key|password|secret|token)\b[^)]*\)"
    "LogGPS" = "Log\.[dewi]\s*\([^)]*\b(gps|location|coordinate|latitude|longitude)\b[^)]*\)"
    "LogCrypto" = "Log\.[dewi]\s*\([^)]*\b(signature|pgp|certificate|crypto|cipher)\b[^)]*\)"
    "LogPII" = "Log\.[dewi]\s*\([^)]*\b(email|phone|ssn|personal)\b[^)]*\)"
    
    # Sensitive data in exceptions/errors
    "ErrorKeys" = "(throw|Result\.failure)\s*\([^)]*\b(key|password|secret|token)\b[^)]*\)"
    "ErrorPII" = "(throw|Result\.failure)\s*\([^)]*\b(email|phone|ssn|personal)\b[^)]*\)"
    
    # Direct byte array to string conversions (potential crypto leaks)
    "ByteArrayToString" = "String\s*\([^)]*ByteArray[^)]*\)"
    "ByteArrayToHex" = "toHex\s*\([^)]*ByteArray[^)]*\)"
    
    # Stack traces with sensitive data
    "PrintStackTrace" = "printStackTrace\(\s*\)"
}

$IssuesFound = @()
$FilesScanned = 0

# Get all Kotlin files
$kotlinFiles = Get-ChildItem -Path $SourcePath -Filter "*.kt" -Recurse

foreach ($file in $kotlinFiles) {
    $FilesScanned++
    $content = Get-Content -Path $file.FullName -Raw
    
    foreach ($patternName in $SensitivePatterns.Keys) {
        $pattern = $SensitivePatterns[$patternName]
        $matches = [regex]::Matches($content, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        
        if ($matches.Count -gt 0) {
            foreach ($match in $matches) {
                $lineNumber = ($content.Substring(0, $match.Index).Split("`n").Length)
                $issue = @{
                    File = $file.FullName.Replace((Get-Location).Path, "").TrimStart('\')
                    Line = $lineNumber
                    Type = $patternName
                    Match = $match.Value.Trim()
                    Severity = if ($patternName -match "Hardcoded|LogKeys|LogGPS|LogCrypto") { "HIGH" } else { "MEDIUM" }
                }
                $IssuesFound += $issue
                
                if ($Verbose) {
                    Write-Host "  [$($issue.Severity)] $($issue.File):$($issue.Line) - $($issue.Type)" -ForegroundColor Yellow
                    Write-Host "    Match: $($issue.Match)" -ForegroundColor Gray
                }
            }
        }
    }
}

# Summary
Write-Host "`nZero-Leak Policy Verification Results:" -ForegroundColor Green
Write-Host "Files scanned: $FilesScanned" -ForegroundColor White
Write-Host "Issues found: $($IssuesFound.Count)" -ForegroundColor $(if ($IssuesFound.Count -eq 0) { "Green" } else { "Red" })

if ($IssuesFound.Count -gt 0) {
    Write-Host "`nIssues by severity:" -ForegroundColor Yellow
    $highSeverityIssues = $IssuesFound | Where-Object { $_.Severity -eq "HIGH" }
    $mediumSeverityIssues = $IssuesFound | Where-Object { $_.Severity -eq "MEDIUM" }
    
    Write-Host "  HIGH: $($highSeverityIssues.Count)" -ForegroundColor Red
    Write-Host "  MEDIUM: $($mediumSeverityIssues.Count)" -ForegroundColor Yellow
    
    Write-Host "`nHigh severity issues:" -ForegroundColor Red
    $highSeverityIssues | ForEach-Object {
        Write-Host "  $($_.File):$($_.Line) - $($_.Type)" -ForegroundColor Red
        Write-Host "    $($_.Match)" -ForegroundColor Gray
    }
    
    Write-Host "`nMedium severity issues:" -ForegroundColor Yellow
    $mediumSeverityIssues | ForEach-Object {
        Write-Host "  $($_.File):$($_.Line) - $($_.Type)" -ForegroundColor Yellow
        Write-Host "    $($_.Match)" -ForegroundColor Gray
    }
    
    Write-Host "`nRecommendations:" -ForegroundColor Cyan
    Write-Host "1. Remove any hardcoded secrets, keys, or passwords" -ForegroundColor White
    Write-Host "2. Ensure sensitive data is not logged (use hash/redacted values instead)" -ForegroundColor White
    Write-Host "3. Avoid converting cryptographic byte arrays to strings directly" -ForegroundColor White
    Write-Host "4. Use proper error handling that doesn't expose sensitive information" -ForegroundColor White
    Write-Host "5. Consider using ProGuard/R8 obfuscation for production builds" -ForegroundColor White
} else {
    Write-Host "✓ No potential information leaks detected!" -ForegroundColor Green
    Write-Host "✓ Zero-leak policy appears to be correctly implemented" -ForegroundColor Green
}

# Exit with appropriate code
exit $(if ($IssuesFound.Count -gt 0) { 1 } else { 0 })
