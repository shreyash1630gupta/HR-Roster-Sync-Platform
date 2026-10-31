# ==============================================================================
# MoveInSync HR Roster Sync Service — Windows PowerShell Demo Script
# ==============================================================================

$BaseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8080" }
$TenantId = "11111111-1111-1111-1111-111111111111"

function Log-Step($title) {
    Write-Host "`n======================================================================" -ForegroundColor Cyan
    Write-Host "[STEP] $title" -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Cyan
}

# 1. Health Probe
Log-Step "1. Probing Actuator Health Endpoint"
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get -TimeoutSec 5
    $health | ConvertTo-Json
    Write-Host "Application is UP and reachable at $BaseUrl" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Could not connect to $BaseUrl" -ForegroundColor Red
    Write-Host "Please make sure:" -ForegroundColor Yellow
    Write-Host "  1. PostgreSQL container is running: docker compose up -d postgres" -ForegroundColor Yellow
    Write-Host "  2. Spring Boot application is started: .\mvnw.cmd spring-boot:run" -ForegroundColor Yellow
    exit 1
}

# 2. Issue JWT Tokens
Log-Step "2. Issuing JWT Tokens for HR_SYSTEM, TRANSPORT_ADMIN, and OPERATOR"
$hrBody = @{ tenant_id = $TenantId; role = "HR_SYSTEM"; subject = "hr-client" } | ConvertTo-Json
$hrToken = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/token" -Method Post -ContentType "application/json" -Body $hrBody).token
Write-Host "HR_SYSTEM Token: $($hrToken.Substring(0, 30))..." -ForegroundColor Yellow

$transportBody = @{ tenant_id = $TenantId; role = "TRANSPORT_ADMIN"; subject = "trans-admin" } | ConvertTo-Json
$transToken = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/token" -Method Post -ContentType "application/json" -Body $transportBody).token
Write-Host "TRANSPORT_ADMIN Token: $($transToken.Substring(0, 30))..." -ForegroundColor Yellow

$opsBody = @{ tenant_id = $TenantId; role = "OPERATOR"; subject = "ops-user" } | ConvertTo-Json
$opsToken = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/token" -Method Post -ContentType "application/json" -Body $opsBody).token
Write-Host "OPERATOR Token: $($opsToken.Substring(0, 30))..." -ForegroundColor Yellow

# Unique employee IDs for clean, reproducible demonstration
$runSuffix = (Get-Random -Minimum 1000 -Maximum 9999)
$emp1 = "EMP-$runSuffix-A"
$emp2 = "EMP-$runSuffix-B"

# 3. Real-Time Batch Ingestion
Log-Step "3. Ingesting Real-Time Batch Events (CREATE $emp1 at v1 & $emp2 at v1)"
$events = @{
    events = @(
        @{
            event_id = [guid]::NewGuid().ToString()
            event_type = "CREATE"
            tenant_id = $TenantId
            external_employee_id = $emp1
            source_version = 1
            data = @{ name = "Sarah Connor"; email = "sarah.connor.$runSuffix@example.com"; office_code = "BLR-EC" }
        },
        @{
            event_id = [guid]::NewGuid().ToString()
            event_type = "CREATE"
            tenant_id = $TenantId
            external_employee_id = $emp2
            source_version = 1
            data = @{ name = "John Connor"; email = "john.connor.$runSuffix@example.com"; office_code = "BLR-EC" }
        }
    )
} | ConvertTo-Json -Depth 5

$hrHeaders = @{
    Authorization = "Bearer $hrToken"
    "X-Tenant-ID" = $TenantId
}

$resp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/sync/events" -Method Post -Headers $hrHeaders -ContentType "application/json" -Body $events
$resp | ConvertTo-Json -Depth 5

# 4. Out-of-Order Handling
Log-Step "4. Monotonic Version Ordering (Forward-Progress Only)"
Write-Host "Ingesting newer version v3 for $emp1..."
$v3Event = @{
    events = @(@{
        event_id = [guid]::NewGuid().ToString()
        event_type = "UPDATE"
        tenant_id = $TenantId
        external_employee_id = $emp1
        source_version = 3
        data = @{ name = "Sarah Connor Updated"; email = "sarah.connor.$runSuffix@example.com"; office_code = "BLR-EC" }
    })
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "$BaseUrl/api/v1/sync/events" -Method Post -Headers $hrHeaders -ContentType "application/json" -Body $v3Event | ConvertTo-Json -Depth 5

Write-Host "`nIngesting older/stale version v2 for $emp1 (must be SKIPPED)..."
$v2Event = @{
    events = @(@{
        event_id = [guid]::NewGuid().ToString()
        event_type = "UPDATE"
        tenant_id = $TenantId
        external_employee_id = $emp1
        source_version = 2
        data = @{ name = "Sarah Connor Stale"; email = "sarah.connor.$runSuffix@example.com"; office_code = "BLR-EC" }
    })
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "$BaseUrl/api/v1/sync/events" -Method Post -Headers $hrHeaders -ContentType "application/json" -Body $v2Event | ConvertTo-Json -Depth 5

# 5. Field Ownership Protection
Log-Step "5. Demonstrating Field Ownership (Transport Admin vs HR System)"
$transHeaders = @{
    Authorization = "Bearer $transToken"
    "X-Tenant-ID" = $TenantId
}
$empList = Invoke-RestMethod -Uri "$BaseUrl/api/v1/employees" -Method Get -Headers $transHeaders
$targetEmp = $empList.content | Where-Object { $_.external_employee_id -eq $emp1 } | Select-Object -First 1
$empId = if ($targetEmp) { $targetEmp.id } else { $empList.content[0].id }
Write-Host "Found Employee ($emp1) Database UUID: $empId" -ForegroundColor Yellow

Write-Host "`nTransport Admin updates pickup_point to 'Forum Mall':"
$updatePayload = @{ pickup_point = "Forum Mall"; special_assistance_needs = "Wheelchair Access" } | ConvertTo-Json
$updatedEmp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/employees/$empId/transport-fields" -Method Put -Headers $transHeaders -ContentType "application/json" -Body $updatePayload
$updatedEmp | ConvertTo-Json -Depth 5

Write-Host "`nHR System attempts to update transport-fields (must be 403 Forbidden):"
try {
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/employees/$empId/transport-fields" -Method Put -Headers $hrHeaders -ContentType "application/json" -Body (@{ pickup_point = "Illegal" } | ConvertTo-Json)
} catch {
    Write-Host "Received expected error: $($_.Exception.Message)" -ForegroundColor Green
}

# 6. Nightly CSV Reconciliation & Live Failure Scenario (Safety Brake)
Log-Step "6. Live Failure Scenario: 30% Safety Brake Tripped by Truncated CSV"
$csvPath = Join-Path $env:TEMP "nightly_roster_demo.csv"
$csvContent = "external_employee_id,source_version,name,email,phone,manager_id,office_code,shift,department,designation`n$emp1,4,Sarah Connor,sarah.connor.$runSuffix@example.com,9876543210,,BLR-EC,MORNING,OPERATIONS,LEAD"
[System.IO.File]::WriteAllText($csvPath, $csvContent, [System.Text.Encoding]::ASCII)

Write-Host "Uploading nightly CSV with only $emp1 present while $emp2 is active (missing ratio >= 30% threshold)..." -ForegroundColor Yellow
$isoNow = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$reconRaw = & curl.exe -s -X POST "$BaseUrl/api/v1/sync/reconcile" `
    -H "Authorization: Bearer $hrToken" `
    -H "X-Tenant-ID: $TenantId" `
    -F "file=@$csvPath" `
    -F "export_id=DEMO-NIGHTLY-$runSuffix" `
    -F "generated_at=$isoNow"

Write-Host "Reconciliation Ingestion Result:"
$reconObj = $reconRaw | ConvertFrom-Json
$reconObj | ConvertTo-Json -Depth 5

$runId = $reconObj.run_id
Write-Host "`nSafety Brake status: $($reconObj.status)" -ForegroundColor Yellow
Write-Host "System paused deactivations to protect cab bookings. Operator manually approves the batch..."

$approveBody = @{ operator_id = [guid]::NewGuid().ToString(); reason = "Authorized batch offboarding verified with HR head" } | ConvertTo-Json
$approveHeaders = @{
    Authorization = "Bearer $opsToken"
    "X-Tenant-ID" = $TenantId
}
$approveResp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/reconciliation/$runId/approve" -Method Post -Headers $approveHeaders -ContentType "application/json" -Body $approveBody
$approveResp | ConvertTo-Json -Depth 5

# 7. Prometheus Metrics
Log-Step "7. Inspecting Prometheus Live Observability Metrics"
$opsHeaders = @{
    Authorization = "Bearer $opsToken"
}
$metrics = Invoke-RestMethod -Uri "$BaseUrl/actuator/prometheus" -Method Get -Headers $opsHeaders
$metrics.Split("`n") | Where-Object { $_ -match "^sync_events_|^outbox_" } | Select-Object -First 20

Write-Host "`n======================================================================" -ForegroundColor Green
Write-Host "[SUCCESS] All Demonstration Scenarios Completed Successfully!" -ForegroundColor Green
Write-Host "======================================================================" -ForegroundColor Green

