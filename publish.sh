#!/bin/sh
#
# Author: daniel.froz@gmail.com
#

VERSION=`cat pom.xml | grep "<version>" | tr -d ' ' | head -1 | sed -E 's/<version\>(.+)\<\/version\>/\1/'`
RELEASE=`echo $VERSION | tr -d ' '`

echo Building release "$RELEASE" using command:
echo docker build --platform linux/amd64 --build-arg release=$RELEASE -t acttio/sync:$RELEASE .
docker build  --platform linux/amd64 --build-arg release=$RELEASE -t acttio/sync:$RELEASE .

echo "Pushing to acttio/sync $RELEASE"
docker push acttio/sync:$RELEASE
