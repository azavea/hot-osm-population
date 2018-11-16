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
    --country botswana \
    --worldpop file:/hot-osm/WorldPop/BWA15v4.tif \
    --qatiles /hot-osm/mbtiles/botswana.mbtiles \
    --model /hot-osm/models/botswana-regression

spark-submit --master "local[*]" --driver-memory 4G \
    --class com.azavea.hotosmpopulation.PredictApp \
    target/scala-2.11/hot-osm-population-assembly.jar \
    --country botswana \
    --worldpop file:/hot-osm/WorldPop/BWA15v4.tif \
    --qatiles /hot-osm/mbtiles/botswana.mbtiles \
    --model /hot-osm/models/botswana-regression \
    --output /hot-osm/botswana.json
```

The arguments appearing before the `hot-osm-population-assembly.jar` are to `spark-submit` command.
The arguments appearing after the JAR are specific to the application:

`country`: `ADM0_A3` country code or name, used to lookup country boundary
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

- `COMMAND`: `train` or `predict`
- `COUNTRY`: Country name to download mbtiles
- `WORLDPOP`: Name of WorldPop tiff or S3 URI to WorldPop tiff

The container may be run locally with:

```sh
docker run -it --rm -v ~/.aws:/root/.aws hotosm-population predict botswana s3://bucket/WorldPop/BWA15v4.tif
# OR
docker run -it --rm -v ~/.aws:/root/.aws hotosm-population predict botswana BWA15v4.tif
```

## Methodology

Our core problem is to estimate the completeness of Open Street Map coverage of building footprints in areas where the map is known to be incomplete.
In order to produce that expectation we need to correlate OSM building footprints with another data set.
We assume population to be the driving factor for building construction so we use [WorldPop](http://www.worldpop.org.uk/) as the independent variable.  
Thus we're attempting to derive a relationship between population and building area used by that population.

### OpenStreetMap 

OSM geometries are sourced from [MapBox OSM QA tiles](https://osmlab.github.io/osm-qa-tiles/). 
They are rasterized to a layer with `CellSize(38.2185,38.2185)` in Web Mercator projection, `EPSG:3857`.
This resolution corresponds to TMS zoom level 15 with 256x256 pixel tiles.
The cell value is the area of the building footprint that intersects pixel footprint in square meters.
If multiple buildings overlap a single pixel their footprints are combined.
       
At this raster resolution resulting pixels cover buildings with sufficient precision:

| Satellite     | Rasterized Buildings |
| ------------- |:--------------------:|
|<img width="400" alt="screen shot 2018-04-30 at 10 43 47 am" src="https://user-images.githubusercontent.com/1158084/39445479-25a400d0-4c89-11e8-9fd8-21fe39ab2e97.png">|<img width="400" alt="screen shot 2018-04-30 at 10 43 29 am" src="https://user-images.githubusercontent.com/1158084/39445478-2594a004-4c89-11e8-90cb-55000150dfd4.png">|
 

### WorldPop

WorldPop raster is provided in `EPSG:4326` with `CellSize(0.0008333, 0.0008333)`, this is close to 100m at equator.
Because we're going to be predicting and reporting in `EPSG:3857` we reproject the raster using `SUM` resample method, aggregating population density values.  

Comparing WorldPop to OSM for Nata, Batswana reveals a problem:   

| WorldPop      | WorldPop + OSM | OSM + Satellite |
| ------------- |:--------------:|:---------------:|
|<img width="256" alt="screen shot 2018-04-30 at 10 41 22 am" src="https://user-images.githubusercontent.com/1158084/39446035-1706ea5e-4c8b-11e8-9925-1118e1b30b3e.png">|<img width="256" alt="screen shot 2018-04-30 at 10 41 39 am" src="https://user-images.githubusercontent.com/1158084/39446036-17173580-4c8b-11e8-8f65-305f61ddb10b.png">|<img width="256" alt="screen shot 2018-04-30 at 10 42 02 am" src="https://user-images.githubusercontent.com/1158084/39446037-1723c548-4c8b-11e8-9caf-e7414ecdca3e.png">|

Even though WorldPop raster resolution is ~100m the population density is spread evenly at `8.2 ppl/pixel` for the blue area.
This makes it difficult to find relation between individual pixel of population and building area, which ranges from 26 to 982 in this area alone.
The conclusion is that we must aggregate both population and building area to the point where they share resolution and aggregate trend may emerge.

Plotting a sample of population vs building area we see this as stacks of multiple area values for single population area:
<img width="1236" alt="screen shot 2018-04-30 at 3 53 20 pm" src="https://user-images.githubusercontent.com/1158084/39447280-4824e25e-4c8f-11e8-99dd-181cf711333b.png">

Its until we reduce aggregate all values at TMS zoom 17 that a clear trend emerges:
<img width="1235" alt="screen shot 2018-04-26 at 1 41 09 pm" src="https://user-images.githubusercontent.com/1158084/39447285-4cb6e308-4c8f-11e8-85b1-1d1af6f89c1b.png">

Finding line of best fit for gives us 6.5 square meters of building footprint per person in Botswana with R-squared value of `0.88069`.
Aggregating to larger areas does not significantly change the fit or the slope of the line.  

### Data Quality

Following data quality issues have been encountered and considered:

Estimates of WorldPop population depend on administrative census data. These estimates data varies in quality and recency from country to country.
To avoid training on data from various sources we fit a model per country where sources are most likely to be consistent, rather than larger areas.
 
WorldPop and OSM are updated at different intervals and do not share coverage. This leads to following problems:

- Regions in WorldPop showing population that are not covered by OSM
- Regions in OSM showing building footprints not covered by WorldPop
- Incomplete OSM coverage per Region

To address above concerns we mark regions where OSM/WorldPop relation appears optimal, this allows us to compare other areas and reveal the above problems using the derived model. For further discussion and examples see [training set](data/README.md) documentation.

### Workflow

Overall workflow as follows:

**Preparation**

- Read WorldPop raster for country
- Reproject WorldPop to `EPSG:3857` TMS Level 15 at `256x256` pixel tiles.
- Rasterize OSM building footprints to `EPSG:3857` TMS Level 15 at `256x256` pixel tiles.
- Aggregate both population and building area to `4x4` tile using `SUM` resample method.

**Training**

- Mask WorldPop and OSM raster by training set polygons.
- Set all OSM `NODATA` cell values to `0.0`.
- Fit and save `LinearRegressionModel` to population vs. building area.
    - Fix y-intercept at '0.0'.
   
**Prediction**
- Apply `LinearRegressionModel` to get expected building area.
- Sum all of `4x4` pixel in a tile.
- Report
    - Total population
    - Actual building area
    - Expected building area
    - Completeness index as `(actual area - expect area) / expected area`

Because the model is derived from well labeled areas which we expect it to be stable in describing the usage building space per country. 
This enables us to re-run the prediction process with updated OSM input and track changes in OSM coverage.   

   
