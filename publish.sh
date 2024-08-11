#!/bin/sh
#
# Author: daniel.froz@gmail.com
#

VERSION=`cat pom.xml | grep "<version>" | tr -d ' ' | head -1 | sed -E 's/<version\>(.+)\<\/version\>/\1/'`
RELEASE=`echo $VERSION | tr -d ' '`

echo Building release "$RELEASE" using command:
echo docker build --platform linux/amd64 --build-arg release=$RELEASE -t registry.digitalocean.com/acttcr/sync:$RELEASE .
docker build  --platform linux/amd64 --build-arg release=$RELEASE -t registry.digitalocean.com/acttcr/sync:$RELEASE .

echo "Pushing to registry.digitalocean.com/acttcr $RELEASE"
docker push registry.digitalocean.com/acttcr/sync:$RELEASE
