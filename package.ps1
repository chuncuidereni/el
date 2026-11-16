# Windows local packaging entry point. Switch to this script's directory so the
# release is independent of the caller's current working directory.
param(
    [ValidateSet("external", "standalone")][string]$Mode,
    [switch]$Offline
)
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

# The release contains a generated .env with deployment credentials. Windows
# ignores Python's POSIX chmod, so protect the directory before the archive is
# created and then protect every generated file with a Windows ACL.
function Set-PrivateAcl {
    param(
        [Parameter(Mandatory = $true)][string]$LiteralPath,
        [switch]$Directory
    )

    $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $trustedSids = @(
        $currentSid,
        [System.Security.Principal.SecurityIdentifier]::new("S-1-5-32-544"),
        [System.Security.Principal.SecurityIdentifier]::new("S-1-5-18")
    ) | Sort-Object -Unique

    $allowedSids = @($trustedSids | ForEach-Object { $_.Value } | Sort-Object -Unique)
    $existing = Get-Acl -LiteralPath $LiteralPath
    $existingSids = @(
        $existing.Access | ForEach-Object {
            $_.IdentityReference.Translate(
                [System.Security.Principal.SecurityIdentifier]
            ).Value
        } | Sort-Object -Unique
    )
    $sameAllowedSids = $existingSids.Count -eq $allowedSids.Count -and
        -not (Compare-Object -ReferenceObject $allowedSids -DifferenceObject $existingSids)
    if ($sameAllowedSids -and $existing.AreAccessRulesProtected) {
        return
    }

    if ($Directory) {
        $acl = [System.Security.AccessControl.DirectorySecurity]::new()
        $acl.SetAccessRuleProtection($true, $false)
        $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
        foreach ($sid in $trustedSids) {
            $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
                $sid,
                [System.Security.AccessControl.FileSystemRights]::FullControl,
                $inheritance,
                [System.Security.AccessControl.PropagationFlags]::None,
                [System.Security.AccessControl.AccessControlType]::Allow
            )
            $acl.AddAccessRule($rule)
        }
    } else {
        $acl = [System.Security.AccessControl.FileSecurity]::new()
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($sid in $trustedSids) {
            $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
                $sid,
                [System.Security.AccessControl.FileSystemRights]::FullControl,
                [System.Security.AccessControl.AccessControlType]::Allow
            )
            $acl.AddAccessRule($rule)
        }
    }

    # Write the access ACL directly. Set-Acl may also attempt to write the audit
    # descriptor after a large archive replacement and require SeSecurityPrivilege.
    if ($Directory) {
        [System.IO.Directory]::SetAccessControl($LiteralPath, $acl)
    } else {
        [System.IO.File]::SetAccessControl($LiteralPath, $acl)
    }

    $verified = Get-Acl -LiteralPath $LiteralPath
    if (-not $verified.AreAccessRulesProtected) {
        throw "ACL inheritance is still enabled: $LiteralPath"
    }
    $verifiedSids = @(
        $verified.Access | ForEach-Object {
            $_.IdentityReference.Translate(
                [System.Security.Principal.SecurityIdentifier]
            ).Value
        } | Sort-Object -Unique
    )
    if ($verifiedSids.Count -ne $allowedSids.Count -or
        (Compare-Object -ReferenceObject $allowedSids -DifferenceObject $verifiedSids)) {
        throw "Unexpected ACL identity on $LiteralPath"
    }
}

$releaseDirectory = Join-Path $PSScriptRoot "release"
if (-not (Test-Path -LiteralPath $releaseDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $releaseDirectory | Out-Null
}
Set-PrivateAcl -LiteralPath $releaseDirectory -Directory

$packageArgs = @("docker/package_release.py", "--profile", "test")
if ($Mode) { $packageArgs += @("--mode", $Mode) }
if ($Offline) { $packageArgs += "--offline" }
try {
    python @packageArgs
    $packageExit = $LASTEXITCODE
} finally {
    $standaloneSecrets = Join-Path $PSScriptRoot ".env.standalone"
    if (Test-Path -LiteralPath $standaloneSecrets) { Set-PrivateAcl -LiteralPath $standaloneSecrets }
}
if ($packageExit -ne 0) { exit $packageExit }

$archivePath = Join-Path $releaseDirectory "el-to-ck-212.tar.gz"
$checksumPath = Join-Path $releaseDirectory "el-to-ck-212.tar.gz.sha256"
Set-PrivateAcl -LiteralPath $archivePath
Set-PrivateAcl -LiteralPath $checksumPath
Write-Host "Release ACLs restricted to the current user, Administrators, and SYSTEM."
