param (
    [switch]$dockerlogin = $false
 )
 
# Memorize script start time
$ScriptStartTime = (Get-Date)

# Change to directory of the powershell script itself
Set-Location $PSScriptRoot

# Build the app
Write-Host "$(Get-Date -Format HH:mm:ss.fff) Building the app" -ForegroundColor Green
mvn clean package
if ($LastExitCode -ne 0) {
    Write-Host "Error: mvn clean package" -ForegroundColor Red
}

# Extract the last build version from the file build.version 
# Extract only the last digits (after the second dot character)
$minorBuildVersion = Select-String -Path .\build.version -Pattern '\d+$' | ForEach-Object { $_.Matches } | ForEach-Object { $_.Value }

# Conver it to an integer and increase version by 1
$minorBuildVersion = $minorBuildVersion -as [int]
$minorBuildVersion++

# Extract the major build version
$majorBuildVersion = Select-String -Path .\build.version -Pattern '^\d+\.\d+\.' | ForEach-Object { $_.Matches } | ForEach-Object { $_.Value }

# Concat existing major with new minor version
$version = $majorBuildVersion + $minorBuildVersion
Write-Host "$(Get-Date -Format HH:mm:ss.fff) Building new container image with version $version" -ForegroundColor Green

# Build and push the Docker container
docker.exe build -q -t kurts/ng_hcm_gcp_mgr:$version .
if ($dockerlogin) {
	docker.exe login -ukurts -pXS7Z8pEy
	}
docker.exe push kurts/ng_hcm_gcp_mgr:$version

# Store the new build version in the file
$version | Out-File -FilePath build.version

# Update the container to the newest image on the deployment on GKE
# deployment name, then container name and finally new image with version tag
Write-Host "$(Get-Date -Format HH:mm:ss.fff) Updating the container image for the kubernetes deployment" -ForegroundColor Green
kubectl.exe set image deployment/hcm-gcp hcm-gcp=kurts/ng_hcm_gcp_mgr:$version

# Show time passed since script started
$ScriptStopTime = (Get-Date)
Write-Host "$(Get-Date -Format HH:mm:ss.fff) Overall script execution time: $(New-Timespan -Start $ScriptStartTime -End $ScriptStopTime)" -ForegroundColor Green

# Wait for GKE to deploy the new container using the new image
Write-Host "$(Get-Date -Format HH:mm:ss.fff) Waiting 15 seconds for the container to start and attaching to its logs" -ForegroundColor Green
Start-Sleep 15

# Attach to the new container's log output
kubectl.exe logs -f $(kubectl.exe get pod -l app=hcm-gcp -o jsonpath="{.items[0].metadata.name}") -c hcm-gcp
