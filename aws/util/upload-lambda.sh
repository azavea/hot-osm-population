#!/usr/bin/env bash
# based on github.com/mapbox/lambda-cfn

# ./util/upload-lambda lambda-directory bucket/prefix

gitSha=$(git rev-parse HEAD)

cd $1
zip -qr $1.zip *
aws s3 cp $1.zip s3://$2/$1.zip --profile hotosm
rm -rf $1.zip