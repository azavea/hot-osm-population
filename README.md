# HOTOSM WorldPop vs OSM Coverage

This projects trains a model of population from WorldPop raster vs OSM building footprint from MapBox QA tiles in order to generate estimates of completeness for OSM building coverage.

## Building

This project uses [Apache Spark](https://spark.apache.org/) and is expected to be run through [`spark-submit`](https://spark.apache.org/docs/latest/submitting-applications.html) command line tool.

In order to generate the assembly `.jar` file required for this tool the following command is used:

```sh
./sbt assembly
```

This will bootstrap the Scala Build Tool and built the assembly and requires only `java` version `1.8` to be available.

## Running

Project defines multiple main files, for training a model of OSM based on WorldPop raster and for predicting OSM coverage based on trained model. They can be called through `spark-submit` as follows:

```sh
spark-submit --master "local[*]" --driver-memory 4G \
    --class com.azavea.hotosmpopulation.TrainApp \
    target/scala-2.11/hot-osm-population-assembly.jar \
    --country BWA \
    --worldpop file:/hot-osm/WorldPop/BWA15v4.tif \
    --qatiles /hot-osm/mbtiles/botswana.mbtiles \
    --model /hot-osm/models/BWA-regression

spark-submit --master "local[*]" --driver-memory 4G \
    --class com.azavea.hotosmpopulation.PredictApp \
    target/scala-2.11/hot-osm-population-assembly.jar \
    --country BWA \
    --worldpop file:/hot-osm/WorldPop/BWA15v4.tif \
    --qatiles /hot-osm/mbtiles/botswana.mbtiles \
    --model /hot-osm/models/BWA-regression \
    --output /hot-osm/botswana-predict-percentage.json
```

The arguments appearing before the `hot-osm-population-assembly.jar` are to `spark-submit` command.
The arguments appearing after the JAR are specific to the application:

`country`: `ADM0_A3` country code, used to lookup country boundary
`worldpop`: URI to WorldPop raster, maybe `file:/` or `s3://` scheme.
`qatiles`: Path to MapBox QA `.mbtiles` file for the country, must be local.
`model`: Path to save/load model directory, is  must be local.
`output`: Path to generate prediction JSON output, must be local.


For development the train and predict commands can are scripted through the `Makefile`:

```sh
make train WORKDIR=/hot-osm
make predict WORKDIR=/hot-osm
```

## `prediction.json`
Because WorldPop and OSM have radically different resolutions for comparison to be valid they need to be aggregated to a common resolution. Specifically WorldPop will identify population centers and evenly spread estimated population for that center over each pixel.  Whereas OSM building footprints are quite resolute and are covered can vary from pixel to pixel on 100m per pixel raster.

Experimentally we see that aggregating the results per tile at zoom level 12 on WebMercator TMS layout produces visually useful relationship.

The output of model prediction is saved as JSON where a record exist for every zoom level 12 TMS tile covering the country.
The key is `"{zoom}/{column}/{row}"` following TMS endpoint convention.

```json
{
    "12/2337/2337": {
        "index": 1.5921462086435942,
        "actual": {
            "pop_sum": 14.647022571414709,
            "osm_sum": 145.48814392089844,
            "osm_avg": 72.74407196044922
        },
        "prediction": {
            "osm_sum": 45872.07371520996,
            "osm_avg": 45.68931644941231
        }
    }
}
```

Units are:
`pop`: estimated population
`osm`: square meters of building footprint

Why `_sum` and `_avg`? While visually the results are aggregated to one result per zoom 12 tile, training and prediction is happening at 16x16 pixels per zoom 12 tile.
At this cell size there is enough smoothing between WorldPop and OSM to start building regressions.

`index` is computed as `(predicted - actual) / predicted`, it will:
- show negative for areas with low OSM coverage for WorldPop population coverage
- show positive for areas with OSM coverage greater than WorldPop population coverage
- stay close to `0` where the ratio of OSM/WorldPop coverage is average 

## Docker
Docker images suitable for AWS Batch can be built and pushed to ECR using:

```sh
make docker
aws ecr get-login --no-include-email --region us-east-1 --profile hotosm
docker login -u AWS ... # copied from output of above command
make push-ecr ECR_REPO=670261699094.dkr.ecr.us-east-1.amazonaws.com/hotosm-population:latest
```

The `ENTRYPOINT` for docker images is `docker/task.sh` which handles the setup for the job.
Note that `task.sh` uses positional arguments where all file references may refer use `s3://` scheme.

The three arguments are are required in order:
`COMMAND`: `train` or `predict`
`COUNTRY`: Country name to download mbtiles
`WORLDPOP`: Name of WorldPop tiff or S3 URI to WorldPop tiff

The container may be run locally with:

```sh
docker run -it --rm -v ~/.aws:/root/.aws hotosm-population predict botswana s3://bucket/WorldPop/BWA15v4.tif
# OR
docker run -it --rm -v ~/.aws:/root/.aws hotosm-population predict botswana BWA15v4.tif
```
