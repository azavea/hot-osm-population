#!/bin/bash

echo COMMAND=$COMMAND
echo COUNTRY=$COUNTRY
echo MODEL=$MODEL
echo OUTPUT=$OUTPUT

set -x # Show debug output
set -e # Fail script on any command error

aws s3 sync $MODEL /task/model/

curl -o - https://s3.amazonaws.com/mapbox/osm-qa-tiles-production/latest.country/$COUNTRY.mbtiles.gz \
    | gunzip > /task/$COUNTRY.mbtiles

JAR=/hot-osm-population-assembly.jar

shopt -s nocasematch
case $COMMAND in
    TRAIN)
        /opt/spark/bin/spark-submit --master "local[*]" --driver-memory 10G \
        --class com.azavea.hotosmpopulation.TrainApp $JAR \
        --worldpop $WORLDPOP \
        --qatiles /task/$COUNTRY.mbtiles \
        --model /task/model \

    ;;
    PREDICT)
        /opt/spark/bin/spark-submit --master "local[*]" --driver-memory 10G \
        --class com.azavea.hotosmpopulation.PredictApp $JAR \
        --country $COUNTRY_CODE \
        --worldpop $WORLDPOP \
        --qatiles /task/$COUNTRY.mbtiles \
        --model /task/model/ \
        --output /task/prediction.json

    # aws s3 cp /task/prediction.json s3://hot-osm/$COUNTRY-prediction.json
    ;;
esac