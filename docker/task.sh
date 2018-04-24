#!/bin/bash

set -x # Show debug output
set -e # Fail script on any command error

COMMAND=$1
COUNTRY=$2
COUNTRY_CODE=$3
WORLDPOP=$4
MODEL=$5
OUTPUT=$6

curl -o - https://s3.amazonaws.com/mapbox/osm-qa-tiles-production/latest.country/${COUNTRY}.mbtiles.gz \
    | gunzip > /task/${COUNTRY}.mbtiles

JAR=/hot-osm-population-assembly.jar

shopt -s nocasematch
case ${COMMAND} in
    TRAIN)
        /opt/spark/bin/spark-submit --master "local[*]" --driver-memory 7G \
        --class com.azavea.hotosmpopulation.TrainApp ${JAR} \
        --country ${COUNTRY_CODE} \
        --worldpop ${WORLDPOP} \
        --model /task/model \
        --qatiles /task/${COUNTRY}.mbtiles

        aws s3 sync /task/model ${MODEL}
    ;;
    PREDICT)
        aws s3 sync ${MODEL} /task/model/

        /opt/spark/bin/spark-submit --master "local[*]" --driver-memory 7G \
        --class com.azavea.hotosmpopulation.PredictApp ${JAR} \
        --country ${COUNTRY_CODE} \
        --worldpop ${WORLDPOP} \
        --qatiles /task/${COUNTRY}.mbtiles \
        --model /task/model/ \
        --output /task/prediction.json

        aws s3 cp /task/prediction.json ${OUTPUT}
    ;;
esac