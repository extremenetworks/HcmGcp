#!/bin/bash
#set -e

# Change to directory of the powershell script itself
BASEDIR=$(dirname "$BASH_SOURCE")
cd $BASEDIR

NC='\033[0m' # No Color
RED='\033[0;31m'
GREEN='\033[0;32m'

# Build the app
echo -e "${GREEN}$(date +%T.%3N) Compiling Java app${NC}"
mvn clean package

# Extract the last build version from the file build.version 
# Extract only the last digits (after the second dot character)
#minorBuildVersion=$(cut -d. -f3 build.version)
minorBuildVersion=$(grep -P -o '\d+$' build.version)

# Increase version by 1
minorBuildVersion="$(($minorBuildVersion+1))"

# Extract the major build version
majorBuildVersion=$(grep -P -o '^\d+\.\d+\.' build.version)

# Concat existing major with new minor version
version="$majorBuildVersion$minorBuildVersion"

echo -e "${GREEN}$(date +%T.%3N) Building new container image with version $version${NC}"

# Build and push the Docker container
docker build -q -t kurts/hcm_gcp:$version .
docker push kurts/hcm_gcp:$version

# Store the new build version in the file
echo $version > build.version

# Update the container to the newest image on the deployment on GKE
# deployment name, then container name and finally new image with version tag
echo -e "${GREEN}$(date +%T.%3N) Updating GKE container image to version $version${NC}"
kubectl set image deployment/hcm-gcp hcm-gcp=kurts/hcm_gcp:$version

echo -e "${GREEN}$(date +%T.%3N) Overall script execution time: $(($SECONDS / 60)) minutes and $(($SECONDS % 60)) seconds${NC}"

# Wait so GKE can deploy the new container using the new image
sleepTime=15
echo -e "$(date +%T.%3N) ${GREEN}Sleeping for $sleepTime seconds before attaching to container logs${NC}"
sleep $sleepTime

# Attach to the new container's log output
kubectl logs -f $(kubectl get pod -l app=hcm-gcp -o jsonpath="{.items[0].metadata.name}") -c hcm-gcp

