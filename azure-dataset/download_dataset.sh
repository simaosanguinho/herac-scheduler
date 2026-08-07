#!/bin/bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
GREEN='\033[0;32m'
NC='\033[0m' # No Color

cd "$DIR" || {
  echo "Redirection failed!"
  exit 1
}

# Ensure that directory exists
mkdir -p input
cd input

echo -e "${GREEN}Downloading Azure dataset...${NC}"
wget https://github.com/Azure/AzurePublicDataset/releases/download/dataset-functions-2019/azurefunctions_dataset2019_azurefunctions-dataset2019.tar.xz
echo -e "${GREEN}Downloading Azure dataset...done${NC}"

echo -e "${GREEN}Extracting Azure dataset...${NC}"
tar -xvf azurefunctions_dataset2019_azurefunctions-dataset2019.tar.xz
rm azurefunctions_dataset2019_azurefunctions-dataset2019.tar.xz
echo -e "${GREEN}Extracting Azure dataset...done${NC}"

echo -e "${GREEN}Check the ./input directory for the dataset files.${NC}"

cd -
