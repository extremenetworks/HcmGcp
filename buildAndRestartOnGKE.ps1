# Change to directory of the powershell script itself
Set-Location $PSScriptRoot

# Delete the current deployment from GKE
kubectl.exe delete -f .\kube_deploy.yaml

# Build the app
$output = mvn clean package
if ($LastExitCode -ne 0) {
    Write-Host "Error: mvn clean package: $output" -ForegroundColor Red
    return
}

# Build and push the Docker container
docker.exe build -q -t kurts/ng_hcm_gcp_mgr .
docker.exe login -ukurts -pXS7Z8pEy
docker.exe push kurts/ng_hcm_gcp_mgr:latest

# Re-create the deployment on GKE
kubectl.exe apply -f .\kube_deploy.yaml