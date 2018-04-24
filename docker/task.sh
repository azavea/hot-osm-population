#!/bin/bash

set -x # Show debug output
set -e # Fail script on any command error

BUCKET=${BUCKET:-hotosm-population}
COMMAND=$1
COUNTRY=$2
WORLDPOP_URI=$3

OSM_QA_URI=https://s3.amazonaws.com/mapbox/osm-qa-tiles-production/latest.country/${COUNTRY}.mbtiles.gz
MODEL_URI=s3://${BUCKET}/models/${COUNTRY}-regression/
OUTPUT_URI=s3://${BUCKET}/predict/${COUNTRY}.json

curl -o - ${OSM_QA_URI} | gunzip > /task/${COUNTRY}.mbtiles

JAR=/hot-osm-population-assembly.jar

shopt -s nocasematch
case ${COMMAND} in
    TRAIN)
        /opt/spark/bin/spark-submit --master "local[*]" --driver-memory 7G \
        --class com.azavea.hotosmpopulation.TrainApp ${JAR} \
        --country ${COUNTRY} \
        --worldpop ${WORLDPOP_URI} \
        --model /task/model \
        --qatiles /task/${COUNTRY}.mbtiles

        aws s3 sync /task/model ${MODEL_URI}
    ;;
    PREDICT)
        aws s3 sync ${MODEL_URI} /task/model/

        /opt/spark/bin/spark-submit --master "local[*]" --driver-memory 7G \
        --class com.azavea.hotosmpopulation.PredictApp ${JAR} \
        --country ${COUNTRY} \
        --worldpop ${WORLDPOP_URI} \
        --qatiles /task/${COUNTRY}.mbtiles \
        --model /task/model/ \
        --output /task/prediction.json

        aws s3 cp /task/prediction.json ${OUTPUT_URI}
    ;;
esac