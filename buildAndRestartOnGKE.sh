#!/bin/bash
set -e

# Change to directory of the powershell script itself
BASEDIR=$(dirname "$BASH_SOURCE")
cd $BASEDIR

NC='\033[0m' # No Color
RED='\033[0;31m'
GREEN='\033[0;32m'

# Build the app
echo -e "${GREEN}$(date +%T.%3N) Compiling Java app${NC}"
mvn clean package

# Delete the existing deployment from GKE
#echo -e "$(date +%T.%3N) ${GREEN}Deleting the current image on GKE${NC}"
#kubectl delete -f kube_deploy.yaml

# Build and push the Docker container
echo -e "$(date +%T.%3N) ${GREEN}Building the new image and pushing to docker registry${NC}"
docker build -q -t kurts/hcm_gcp .
docker push kurts/hcm_gcp:latest

# Re-create the deployment on GKE
echo -e "$(date +%T.%3N) ${GREEN}Deploying the new image on GKE${NC}"
kubectl apply -f kube_deploy.yaml