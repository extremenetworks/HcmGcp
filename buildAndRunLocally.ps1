# Change to directory of the powershell script itself
cd $PSScriptRoot

# Build the app
$output = mvn clean package
if ($LastExitCode -ne 0) {
    Write-Host "Error: mvn clean package: $output" -ForegroundColor Red
    return
}

# Build the Docker container
docker build -q -t kurts/ng_hcm_gcp_mgr .

# Run it locally and attach to stdout/stdin
docker run -it --rm -p 8282:8080 kurts/ng_hcm_gcp_mgr